package mfs.mw.auth.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import mfs.mw.auth.TestInfrastructure;
import mfs.mw.auth.ValkeyInfrastructure;
import mfs.mw.auth.client.CryptoClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The topics mw-auth consumes for the PIN lifecycle over Kafka (ticket 70): {@code
 * auth.wallet-lock.v1} (LOCK, UNLOCK, REVOKE_SESSIONS; once per commandId; an unknown wallet skipped;
 * a malformed record parked on the retry topic) and the core's answer to a PIN reset's
 * VerifyCustomerFactors on {@code core.wallet.event.v1}.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"mw.auth.consumer.enabled=true", "mw.kafka.retry.delays=1s,1s,1s"})
@Import({TestInfrastructure.class, ValkeyInfrastructure.class, AuthConsumedTopics.class})
@DirtiesContext
class WalletLockKafkaIT {

  static final String TOPIC = "auth.wallet-lock.v1";
  static final String CORE_EVENTS = "core.wallet.event.v1";

  private final EventPublisher publisher;
  private final JdbcTemplate jdbc;
  private final StringRedisTemplate valkey;

  @MockitoBean CryptoClient crypto;

  String wallet;
  String deviceId;

  @Autowired
  WalletLockKafkaIT(EventPublisher publisher, JdbcTemplate jdbc, StringRedisTemplate valkey) {
    this.publisher = publisher;
    this.jdbc = jdbc;
    this.valkey = valkey;
  }

  @BeforeEach
  void wallet() {
    wallet = "0171" + String.format("%07d", (int) (Math.random() * 10_000_000));
    deviceId = "dev-" + UUID.randomUUID();
    jdbc.update("insert into wallet (wallet_id, msisdn_token, msisdn_token_kid, msisdn_enc, type, status, has_pin, version)"
        + " values (?, ?, 'tokenise-msisdn.v1', '{}'::jsonb, 'CUSTOMER', 'ACTIVE', true, 1)", wallet, "tok-" + wallet);
    jdbc.update("insert into device (device_id, wallet_id, public_key, bound_at) values (?, ?, 'pk', now())",
        deviceId, wallet);
    jdbc.update("insert into refresh_token (token_hash, family, wallet_id, device_id, expires_at)"
        + " values (?, ?, ?, ?, now() + interval '1 day')", "rt-" + wallet, UUID.randomUUID(), wallet, deviceId);
    valkey.opsForValue().set("sess:" + wallet + ":" + deviceId, "300", Duration.ofMinutes(5));
  }

  private String command(String action, String walletId) {
    String id = UUID.randomUUID().toString();
    send(id, action, walletId);
    return id;
  }

  private void send(String commandId, String action, String walletId) {
    Map<String, Object> c = new LinkedHashMap<>();
    c.put("schemaVersion", 1);
    c.put("commandId", commandId);
    c.put("action", action);
    c.put("walletId", walletId);
    c.put("reason", "it");
    c.put("actor", Map.of("type", "user", "id", "ops-checker01"));
    c.put("correlationId", "corr-it");
    c.put("issuedAt", Instant.now().toString());
    publisher.publish(TOPIC, walletId, new EventMetadata("corr-it", commandId, commandId, 1), c);
  }

  private boolean processed(String consumer, String eventId) {
    return jdbc.queryForObject("select count(*) from inbox where consumer = ? and event_id = ?", Long.class, consumer,
        eventId) == 1;
  }

  private static void await(BooleanSupplier condition) {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not met within 30 s");
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
  }

  private int locks(String kind) {
    return jdbc.queryForObject("select count(*) from lock where wallet_id = ? and kind = ?", Integer.class, wallet, kind);
  }

  private boolean revoked() {
    return jdbc.queryForObject("select revoked from refresh_token where token_hash = ?", Boolean.class, "rt-" + wallet);
  }

  @Test
  void lockEndsTheSessionsAndUnlockLiftsTheAdminAndThePinLock() {
    String lock = command("LOCK", wallet);
    await(() -> processed("mw-auth.wallet-lock", lock));
    assertThat(locks("ADMIN")).isEqualTo(1);
    assertThat(revoked()).isTrue();
    assertThat(valkey.hasKey("sess:" + wallet + ":" + deviceId)).isFalse();
    assertThat(jdbc.queryForObject("select count(*) from outbox where topic = 'mw.audit.event.v1'"
        + " and payload->>'action' = 'auth.wallet.locked' and payload->'actor'->>'id' = 'ops-checker01'", Integer.class))
        .isPositive();

    jdbc.update("insert into lock (wallet_id, kind, level, until, reason, actor) values (?, 'PIN', 3, null, 'WRONG_PIN', 'mw-auth')",
        wallet);
    jdbc.update("insert into attempt (wallet_id, window_started_at, count) values (?, now(), 2)", wallet);
    String unlock = command("UNLOCK", wallet);
    await(() -> processed("mw-auth.wallet-lock", unlock));
    assertThat(locks("ADMIN")).isZero();
    assertThat(locks("PIN")).isZero();
    assertThat(jdbc.queryForObject("select count(*) from attempt where wallet_id = ?", Integer.class, wallet)).isZero();
  }

  @Test
  void aCommandIsAppliedOnceAndRevokeSessionsLocksNothing() {
    String id = UUID.randomUUID().toString();
    send(id, "LOCK", wallet);
    await(() -> processed("mw-auth.wallet-lock", id));
    jdbc.update("delete from lock where wallet_id = ?", wallet);
    send(id, "LOCK", wallet);
    String revoke = command("REVOKE_SESSIONS", wallet);
    await(() -> processed("mw-auth.wallet-lock", revoke));
    assertThat(locks("ADMIN")).as("the replayed LOCK changed nothing").isZero();
    assertThat(revoked()).isTrue();
  }

  @Test
  void anUnknownWalletIsSkippedAndAMalformedRecordGoesToTheRetryTopic() {
    String unknown = command("LOCK", "01799999999");
    Map<String, Object> bad = Map.of("schemaVersion", 1, "commandId", "bad-1", "action", "DELETE", "walletId", wallet);
    publisher.publish(TOPIC, wallet, new EventMetadata("corr-it", "bad-1", "bad-1", 1), bad);
    String marker = command("REVOKE_SESSIONS", wallet);
    await(() -> processed("mw-auth.wallet-lock", marker));
    assertThat(processed("mw-auth.wallet-lock", unknown)).isFalse();
    assertThat(locks("ADMIN")).isZero();
  }

  @Test
  void anApprovedCarePinResetOpensTheResetOnceFromTheCommittedCommand() {
    UUID cr = UUID.randomUUID();
    Map<String, Object> c = new LinkedHashMap<>();
    c.put("schemaVersion", 1);
    c.put("commandId", cr + ":pin-reset");
    c.put("action", "PIN_RESET");
    c.put("walletId", wallet);
    c.put("reason", "lost phone");
    c.put("actor", Map.of("type", "user", "id", "ops-checker01"));
    c.put("changeRequestId", cr.toString());
    c.put("makerId", "ops-maker01");
    c.put("correlationId", "corr-it");
    c.put("issuedAt", Instant.now().toString());
    org.mockito.BDDMockito.given(crypto.decrypt(org.mockito.ArgumentMatchers.any()))
        .willReturn(wallet.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    publisher.publish(TOPIC, wallet, new EventMetadata("corr-it", cr.toString(), cr + ":pin-reset", 1), c);
    publisher.publish(TOPIC, wallet, new EventMetadata("corr-it", cr.toString(), cr + ":pin-reset", 1), c);
    String marker = command("REVOKE_SESSIONS", wallet);
    await(() -> processed("mw-auth.wallet-lock", marker));
    assertThat(jdbc.queryForObject("select count(*) from pin_reset where change_request_id = ?", Integer.class, cr))
        .as("a redelivery opens nothing twice").isEqualTo(1);
    assertThat(jdbc.queryForObject("select status || '/' || kind from pin_reset where change_request_id = ?",
        String.class, cr)).isEqualTo("APPROVED/CARE");
    assertThat(jdbc.queryForObject("select count(*) from outbox where topic = 'mw.notify.request.v1'"
        + " and payload->>'templateCode' = 'PIN_RESET_APPROVED'", Integer.class)).isEqualTo(1);
  }

  @Test
  void theCoresAnswerOnTheWalletEventTopicMovesAPendingReset() {
    UUID reset = UUID.randomUUID();
    jdbc.update("insert into pin_reset (id, wallet_id, kind, status, requested_by, device_id, expires_at, command_id)"
        + " values (?, ?, 'SELF', 'PENDING_KYC', 'wallet', ?, now() + interval '15 minutes', ?)",
        reset, wallet, deviceId, "pin-reset:" + reset);
    String txn = UUID.randomUUID().toString();
    txn = txn.substring(0, 14) + "7" + txn.substring(15);
    String eventId = UUID.randomUUID().toString();
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("schemaVersion", 1);
    e.put("eventType", "CustomerFactorsVerified");
    e.put("eventId", eventId);
    e.put("commandId", "pin-reset:" + reset);
    e.put("txnId", txn);
    e.put("coreRef", "CORE-1");
    e.put("occurredAt", Instant.now().toString());
    e.put("outcome", "SUCCESS");
    e.put("payload", Map.of("walletId", wallet, "resetId", reset.toString()));
    publisher.publish(CORE_EVENTS, txn, new EventMetadata(txn, txn, "pin-reset:" + reset, 1), e);
    await(() -> processed("mw-auth.pin-reset", eventId));
    assertThat(jdbc.queryForObject("select status from pin_reset where id = ?", String.class, reset)).isEqualTo("VERIFIED");
  }
}

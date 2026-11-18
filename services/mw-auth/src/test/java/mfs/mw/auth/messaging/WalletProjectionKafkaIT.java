package mfs.mw.auth.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import mfs.mw.auth.TestInfrastructure;
import mfs.mw.auth.client.CryptoClient;
import mfs.mw.auth.dto.CryptoEnvelope;
import mfs.mw.auth.entity.AuthWallet;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import mfs.mw.auth.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The wallet projection over Kafka (ticket 67, D5): {@code WalletStateChanged} on {@code
 * core.wallet.event.v1} upserts {@code auth.wallet} when its version is newer; a duplicate event
 * (same eventId) and an older version change nothing; other core events on the topic are skipped.
 * A new wallet's MSISDN is tokenised and encrypted through mw-crypto (here a mock); mw-crypto down
 * fails closed: the record goes to the retry topics and lands once mw-crypto is back.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"mw.auth.consumer.enabled=true", "mw.kafka.retry.delays=1s,1s,1s"})
@Import({TestInfrastructure.class, AuthConsumedTopics.class})
@DirtiesContext
class WalletProjectionKafkaIT {

  static final String TOPIC = "core.wallet.event.v1";
  static final CryptoEnvelope ENVELOPE =
      new CryptoEnvelope("kek-column.v1", "d3JhcA==", "AAAAAAAAAAAAAAAA", "Y2lwaGVy", "MDE3MTEwMDAwMDE=");

  private final EventPublisher publisher;
  private final WalletRepository wallets;
  private final JdbcTemplate jdbc;

  @MockitoBean CryptoClient crypto;

  @Autowired
  WalletProjectionKafkaIT(EventPublisher publisher, WalletRepository wallets, JdbcTemplate jdbc) {
    this.publisher = publisher;
    this.wallets = wallets;
    this.jdbc = jdbc;
  }

  @BeforeEach
  void cryptoUp() {
    given(crypto.tokeniseMsisdn(anyString()))
        .willAnswer(inv -> new CryptoClient.MsisdnToken("MSISDN_" + inv.getArgument(0, String.class), "tokenise-msisdn.v1"));
    given(crypto.encrypt(eq(CryptoClient.KEK_COLUMN), any(), any())).willReturn(ENVELOPE);
  }

  static String txnId() {
    String u = UUID.randomUUID().toString();
    return u.substring(0, 14) + "7" + u.substring(15);
  }

  /** Publishes a WalletStateChanged as mw-core-mock does (key walletId); returns its eventId. */
  private String publish(String eventId, String walletId, String msisdn, String status, long version) {
    String txn = txnId();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("walletId", walletId);
    payload.put("msisdn", msisdn);
    payload.put("walletType", "CUSTOMER");
    payload.put("status", status);
    payload.put("hasPin", true);
    payload.put("version", version);
    payload.put("change", "STATUS_CHANGED");
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("schemaVersion", 1);
    event.put("eventType", "WalletStateChanged");
    event.put("eventId", eventId);
    event.put("commandId", "wallet-state:" + txn);
    event.put("txnId", txn);
    event.put("coreRef", "WALLET-V" + version);
    event.put("occurredAt", Instant.now().toString());
    event.put("outcome", "SUCCESS");
    event.put("payload", payload);
    publisher.publish(TOPIC, walletId, new EventMetadata(txn, txn, eventId, 1), event);
    return eventId;
  }

  private static void await(BooleanSupplier condition) {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not met within 30 s");
      }
      Thread.onSpinWait();
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
  }

  private boolean processed(String eventId) {
    return jdbc.queryForObject("select count(*) from inbox where event_id = ?", Long.class, eventId) == 1;
  }

  @Test
  void aNewWalletIsProjectedWithItsMsisdnHashedAndEncrypted() {
    String id = "01711000101";
    String e = publish(UUID.randomUUID().toString(), id, "+88001711000101", "ACTIVE", 10);
    await(() -> processed(e));

    AuthWallet w = wallets.find(id).orElseThrow();
    assertThat(w.status()).isEqualTo("ACTIVE");
    assertThat(w.type()).isEqualTo("CUSTOMER");
    assertThat(w.version()).isEqualTo(10);
    assertThat(w.msisdnToken()).isEqualTo("MSISDN_01711000101");
    assertThat(w.msisdnTokenKid()).isEqualTo("tokenise-msisdn.v1");
    assertThat(w.msisdnEnc()).contains("kek-column.v1");
    // normalised to the national form, bound to the wallet id
    verify(crypto).tokeniseMsisdn("01711000101");
    verify(crypto).encrypt(CryptoClient.KEK_COLUMN, "01711000101".getBytes(StandardCharsets.UTF_8),
        id.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void outOfOrderVersionsKeepTheNewestStateAndAKnownWalletNeedsNoCryptoCall() {
    String id = "01711000102";
    String v5 = publish(UUID.randomUUID().toString(), id, id, "ACTIVE", 5);
    await(() -> processed(v5));
    String v9 = publish(UUID.randomUUID().toString(), id, id, "LOCKED", 9);
    await(() -> processed(v9));
    String v7 = publish(UUID.randomUUID().toString(), id, id, "ACTIVE", 7);
    await(() -> processed(v7));

    assertThat(wallets.find(id).orElseThrow().status()).isEqualTo("LOCKED");
    assertThat(wallets.version(id)).contains(9L);
    verify(crypto, times(1)).tokeniseMsisdn(id);
  }

  @Test
  void aDuplicateEventIsAppliedOnce() {
    String id = "01711000103";
    String eventId = UUID.randomUUID().toString();
    publish(eventId, id, id, "ACTIVE", 3);
    await(() -> processed(eventId));
    // as if a later change had been undone in the core's view: only the inbox can refuse the replay
    jdbc.update("update wallet set status = 'HOLD', version = 2 where wallet_id = ?", id);

    publish(eventId, id, id, "ACTIVE", 3);
    // the same key keeps the order: once this one is in the inbox, the replay was consumed
    String marker = publish(UUID.randomUUID().toString(), id, id, "BLOCKED", 1);
    await(() -> processed(marker));

    assertThat(wallets.find(id).orElseThrow().status()).isEqualTo("HOLD");
    assertThat(wallets.version(id)).contains(2L);
  }

  @Test
  void otherCoreEventsOnTheTopicAreSkipped() {
    String txn = txnId();
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("schemaVersion", 1);
    event.put("eventType", "TransferPosted");
    event.put("eventId", UUID.randomUUID().toString());
    event.put("commandId", txn + ":post");
    event.put("txnId", txn);
    event.put("coreRef", "CORE-1");
    event.put("occurredAt", Instant.now().toString());
    event.put("outcome", "SUCCESS");
    event.put("payload", Map.of());
    publisher.publish(TOPIC, txn, new EventMetadata(txn, txn, txn + ":post", 1), event);
    String marker = publish(UUID.randomUUID().toString(), "01711000104", "01711000104", "ACTIVE", 1);
    await(() -> processed(marker));

    assertThat(jdbc.queryForObject("select count(*) from inbox where event_id = ?", Long.class, event.get("eventId")))
        .isZero();
  }

  @Test
  void mwCryptoDownFailsClosedAndTheRetryTopicAppliesTheEventLater() {
    String id = "01711000105";
    given(crypto.tokeniseMsisdn(id))
        .willThrow(new MwException(ErrorCodes.SYS_CRYPTO_UNAVAILABLE, "down"))
        .willReturn(new CryptoClient.MsisdnToken("MSISDN_" + id, "tokenise-msisdn.v1"));
    String e = publish(UUID.randomUUID().toString(), id, id, "ACTIVE", 4);

    await(() -> processed(e));
    assertThat(wallets.find(id).orElseThrow().version()).isEqualTo(4);
    verify(crypto, times(2)).tokeniseMsisdn(id);
  }
}

package mfs.mw.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import mfs.mw.auth.TestInfrastructure;
import mfs.mw.auth.entity.AuthWallet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code auth.wallet} (ticket 67, D5): the projection keeps the highest version; an equal or older
 * one changes nothing. The MSISDN columns are written once, on insert.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class WalletRepositoryIT {

  static final String ENC = "{\"kekId\":\"kek-column.v1\",\"wrappedKey\":\"d3JhcA==\",\"iv\":\"AAAAAAAAAAAAAAAA\",\"ciphertext\":\"Y2lwaGVy\"}";

  private final WalletRepository wallets;
  private final JdbcTemplate jdbc;

  @Autowired
  WalletRepositoryIT(WalletRepository wallets, JdbcTemplate jdbc) {
    this.wallets = wallets;
    this.jdbc = jdbc;
  }

  @BeforeEach
  void empty() {
    jdbc.execute("truncate wallet, credential, attempt, lock, device, refresh_token, pin_reset");
  }

  static AuthWallet wallet(String id, String status, long version) {
    return new AuthWallet(id, "MSISDN_TOKEN_" + id, "tokenise-msisdn.v1", ENC, "CUSTOMER", status, true, null, version);
  }

  @Test
  void aNewWalletIsInsertedAndReadBack() {
    assertThat(wallets.upsertIfNewer(wallet("01711000001", "ACTIVE", 5))).isTrue();

    AuthWallet w = wallets.find("01711000001").orElseThrow();
    assertThat(w).usingRecursiveComparison().ignoringFields("msisdnEnc").isEqualTo(wallet("01711000001", "ACTIVE", 5));
    assertThat(w.msisdnEnc()).contains("kek-column.v1"); // jsonb: same object, its own spacing
    assertThat(wallets.version("01711000001")).contains(5L);
    assertThat(wallets.findByMsisdnToken("MSISDN_TOKEN_01711000001")).contains(w);
  }

  @Test
  void aHigherVersionReplacesTheStateButKeepsTheMsisdnColumns() {
    wallets.upsertIfNewer(wallet("01711000001", "ACTIVE", 5));
    AuthWallet later =
        new AuthWallet("01711000001", "OTHER", "tokenise-msisdn.v2", "{}", "AGENT", "LOCKED", false, 1990, 9);

    assertThat(wallets.upsertIfNewer(later)).isTrue();

    AuthWallet w = wallets.find("01711000001").orElseThrow();
    assertThat(w.status()).isEqualTo("LOCKED");
    assertThat(w.type()).isEqualTo("AGENT");
    assertThat(w.hasPin()).isFalse();
    assertThat(w.birthYear()).isEqualTo(1990);
    assertThat(w.version()).isEqualTo(9);
    assertThat(w.msisdnToken()).isEqualTo("MSISDN_TOKEN_01711000001");
    assertThat(w.msisdnEnc()).contains("kek-column.v1");
  }

  @Test
  void anEqualOrLowerVersionChangesNothing() {
    wallets.upsertIfNewer(wallet("01711000001", "LOCKED", 9));

    assertThat(wallets.upsertIfNewer(wallet("01711000001", "ACTIVE", 9))).isFalse();
    assertThat(wallets.upsertIfNewer(wallet("01711000001", "ACTIVE", 3))).isFalse();
    assertThat(wallets.find("01711000001").orElseThrow().status()).isEqualTo("LOCKED");
  }

  @Test
  void anUnknownWalletIsEmpty() {
    assertThat(wallets.find("01799999999")).isEmpty();
    assertThat(wallets.version("01799999999")).isEmpty();
  }

  @Test
  void theSchemaRefusesAStatusOutsideTheCoresSet() {
    assertThatThrownBy(() -> wallets.upsertIfNewer(wallet("01711000001", "CLOSED", 1)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void theOtherTablesOfTheModelExistAndReferenceTheWallet() {
    wallets.upsertIfNewer(wallet("01711000001", "ACTIVE", 1));
    jdbc.update("insert into credential (wallet_id, pin_hmac, hmac_key_id, set_at) values (?, 'h', 'pin-hmac.v1', now())",
        "01711000001");
    jdbc.update("insert into attempt (wallet_id, window_started_at) values (?, now())", "01711000001");
    jdbc.update("insert into lock (wallet_id, kind, reason, actor) values (?, 'PIN', 'WRONG_PIN', 'system')", "01711000001");
    jdbc.update("insert into device (device_id, wallet_id, public_key, bound_at) values ('d1', ?, 'pk', now())", "01711000001");
    jdbc.update("insert into refresh_token (token_hash, family, wallet_id, device_id, expires_at) "
        + "values ('h1', gen_random_uuid(), ?, 'd1', now() + interval '30 days')", "01711000001");
    jdbc.update("insert into pin_reset (id, wallet_id, kind, status, requested_by) "
        + "values (gen_random_uuid(), ?, 'SELF', 'PENDING_KYC', 'self')", "01711000001");

    assertThatThrownBy(() -> jdbc.update(
            "insert into device (device_id, wallet_id, public_key, bound_at) values ('d2', ?, 'pk', now())", "01711000001"))
        .as("one bound device per wallet")
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update(
            "insert into pin_reset (id, wallet_id, kind, status, requested_by) "
                + "values (gen_random_uuid(), ?, 'CARE', 'APPROVED', 'ops')", "01711000001"))
        .as("one open reset per wallet")
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update(
            "insert into credential (wallet_id, pin_hmac, hmac_key_id, set_at) values ('01799999999', 'h', 'k', now())"))
        .as("a credential needs its wallet")
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}

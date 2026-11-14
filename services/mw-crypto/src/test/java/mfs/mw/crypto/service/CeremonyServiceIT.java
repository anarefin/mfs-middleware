package mfs.mw.crypto.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import mfs.mw.crypto.TestInfrastructure;
import mfs.mw.crypto.client.FakeCryptoProvider;
import mfs.mw.crypto.dto.CeremonyComponentRequest;
import mfs.mw.crypto.dto.CeremonyView;
import mfs.mw.crypto.dto.KeyChangeRequest;
import mfs.mw.crypto.dto.KeyView;
import mfs.mw.crypto.exception.MwException;
import mfs.mw.crypto.util.CeremonyComponents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The key ceremony and approved key changes (ticket 75) against the real {@code crypto} tables with
 * an in-memory provider: the share log holds who and when only, the two custodians must differ, the
 * key is usable only after commit, abort destroys the staged objects, changes replay idempotently.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"mw.crypto.pkcs11.enabled=false", "mw.crypto.bootstrap-keys=false"})
@Import({TestInfrastructure.class, CeremonyServiceIT.Fake.class})
class CeremonyServiceIT {

  @TestConfiguration(proxyBeanMethods = false)
  static class Fake {
    @Bean
    FakeCryptoProvider fakeCryptoProvider() {
      return new FakeCryptoProvider();
    }
  }

  static final SecureRandom RANDOM = new SecureRandom();

  final CeremonyService ceremonies;
  final KeyChangeService changes;
  final KeyService keys;
  final FakeCryptoProvider hsm;
  final JdbcClient jdbc;

  @Autowired
  CeremonyServiceIT(
      CeremonyService ceremonies, KeyChangeService changes, KeyService keys, FakeCryptoProvider hsm, JdbcClient jdbc) {
    this.ceremonies = ceremonies;
    this.changes = changes;
    this.keys = keys;
    this.hsm = hsm;
    this.jdbc = jdbc;
  }

  @BeforeEach
  void clean() {
    jdbc.sql("DELETE FROM custodian_share_log").update();
    jdbc.sql("DELETE FROM ceremony").update();
    jdbc.sql("DELETE FROM key_change").update();
    jdbc.sql("DELETE FROM key").update();
    hsm.clear();
    hsm.down(false);
  }

  static byte[] component() {
    byte[] c = new byte[32];
    do {
      RANDOM.nextBytes(c);
    } while (!CeremonyComponents.valid(c));
    return c;
  }

  static CeremonyComponentRequest share(String keyId, String custodian, byte[] c) {
    return new CeremonyComponentRequest(keyId, "PIN_HMAC", custodian, HexFormat.of().formatHex(c),
        CeremonyComponents.checkValue(c));
  }

  static String code(Throwable e) {
    assertThat(e).isInstanceOf(MwException.class);
    return ((MwException) e).code();
  }

  @Test
  void twoCustodiansThenCommitMakesTheActiveVersion() {
    UUID id = UUID.randomUUID();
    byte[] c1 = component();
    byte[] c2 = component();
    assertThat(ceremonies.open(id, "pin-hmac", "PIN_HMAC").state()).isEqualTo("OPEN");
    // opening again with the same values is the same ceremony
    assertThat(ceremonies.open(id, "pin-hmac", "PIN_HMAC").kid()).isEqualTo("pin-hmac.v1");
    assertThat(code(catchThrowable(() -> ceremonies.open(id, "other-key", "PIN_HMAC")))).isEqualTo("MW-VAL-018");

    CeremonyView first = ceremonies.component(id, share("pin-hmac", "sec1", c1));
    assertThat(first.state()).isEqualTo("OPEN");
    assertThat(first.kid()).isEqualTo("pin-hmac.v1");
    assertThat(hsm.labels()).containsExactly("ceremony-" + id + ".c1");

    assertThat(code(catchThrowable(() -> ceremonies.component(id, share("pin-hmac", "sec1", c2)))))
        .isEqualTo("MW-VAL-018");

    CeremonyView done = ceremonies.component(id, share("pin-hmac", "sec2", c2));
    assertThat(done.state()).isEqualTo("COMBINED");
    assertThat(done.checkValue()).isEqualTo(CeremonyComponents.keyCheckValueOf(CeremonyComponents.combined(c1, c2)));
    assertThat(done.shares()).extracting(CeremonyView.Share::custodian).containsExactly("sec1", "sec2");
    assertThat(hsm.labels()).containsExactly("pin-hmac.v1");
    assertThat(keys.list().items()).isEmpty();

    // the share log has who and when only
    List<String> columns = jdbc.sql(
            "SELECT column_name FROM information_schema.columns WHERE table_name = 'custodian_share_log'")
        .query(String.class).list();
    assertThat(columns).containsExactlyInAnyOrder("ceremony_id", "share", "custodian", "entered_at");

    assertThat(code(catchThrowable(() -> ceremonies.commit(id, "sec1", "sec1")))).isEqualTo("MW-VAL-018");
    KeyView key = ceremonies.commit(id, "sec1", "sec2");
    assertThat(key.versions()).singleElement().satisfies(v -> assertThat(v.state()).isEqualTo("ACTIVE"));
    // a repeat answers the same key
    assertThat(ceremonies.commit(id, "sec1", "sec2").versions()).hasSize(1);
    assertThat(code(catchThrowable(() -> ceremonies.abort(id)))).isEqualTo("MW-VAL-018");
  }

  @Test
  void commitBeforeBothSharesAndAWrongCheckValueAreRefused() {
    UUID id = UUID.randomUUID();
    byte[] c1 = component();
    ceremonies.open(id, "pin-hmac", "PIN_HMAC");
    CeremonyComponentRequest bad =
        new CeremonyComponentRequest("pin-hmac", "PIN_HMAC", "sec1", HexFormat.of().formatHex(c1), "000000");
    assertThat(code(catchThrowable(() -> ceremonies.component(id, bad)))).isEqualTo("MW-VAL-017");

    ceremonies.component(id, share("pin-hmac", "sec1", c1));
    assertThat(code(catchThrowable(() -> ceremonies.commit(id, "a", "b")))).isEqualTo("MW-VAL-018");
    // another ceremony of the same key cannot start while this one is live
    assertThat(code(catchThrowable(() -> ceremonies.open(UUID.randomUUID(), "pin-hmac", "PIN_HMAC"))))
        .isEqualTo("MW-VAL-018");
  }

  @Test
  void aKekIsNotForTheCeremonyYet() {
    byte[] c = component();
    CeremonyComponentRequest req = new CeremonyComponentRequest("kek-column", "KEK_COLUMN", "sec1", HexFormat.of().formatHex(c),
        CeremonyComponents.checkValue(c));
    assertThat(code(catchThrowable(() -> ceremonies.open(UUID.randomUUID(), req.keyId(), req.purpose())))).isEqualTo("MW-VAL-015");
  }

  @Test
  void abortDestroysTheStagedObjects() {
    UUID id = UUID.randomUUID();
    ceremonies.open(id, "pin-hmac", "PIN_HMAC");
    ceremonies.component(id, share("pin-hmac", "sec1", component()));
    ceremonies.component(id, share("pin-hmac", "sec2", component()));
    ceremonies.abort(id);
    assertThat(hsm.labels()).isEmpty();
    assertThat(ceremonies.get(id).state()).isEqualTo("ABORTED");
    ceremonies.abort(id);
    ceremonies.abort(UUID.randomUUID());
  }

  @Test
  void aComponentNeedsAnOpenCeremonyAndAbortIsTerminalPerId() {
    // a component of a ceremony never opened is refused: entry never opens one
    assertThat(code(catchThrowable(() -> ceremonies.component(UUID.randomUUID(), share("pin-hmac", "sec1", component())))))
        .isEqualTo("MW-VAL-003");
    // an abort that arrives first (the request was rejected) leaves a tombstone: no later open or component
    UUID late = UUID.randomUUID();
    ceremonies.abort(late);
    assertThat(ceremonies.get(late).state()).isEqualTo("ABORTED");
    assertThat(code(catchThrowable(() -> ceremonies.open(late, "pin-hmac", "PIN_HMAC")))).isEqualTo("MW-VAL-018");
    assertThat(code(catchThrowable(() -> ceremonies.component(late, share("pin-hmac", "sec1", component())))))
        .isEqualTo("MW-VAL-018");
    // a component after the abort of an open one is refused and nothing stays in the HSM
    UUID id = UUID.randomUUID();
    ceremonies.open(id, "pin-hmac", "PIN_HMAC");
    ceremonies.component(id, share("pin-hmac", "sec1", component()));
    ceremonies.abort(id);
    assertThat(code(catchThrowable(() -> ceremonies.component(id, share("pin-hmac", "sec2", component())))))
        .isEqualTo("MW-VAL-018");
    assertThat(hsm.labels()).isEmpty();
    // and a new ceremony of the key can start
    assertThat(ceremonies.open(UUID.randomUUID(), "pin-hmac", "PIN_HMAC").state()).isEqualTo("OPEN");
  }

  @Test
  void theStateOfAKeyChangeIsReadByItsId() {
    UUID id = UUID.randomUUID();
    assertThat(changes.state(id).applied()).isFalse();
    changes.apply(id, new KeyChangeRequest(KeyChangeRequest.Action.GENERATE, "hmac-y", "HMAC_Y", null, "a", "b"));
    var state = changes.state(id);
    assertThat(state.applied()).isTrue();
    assertThat(state.action()).isEqualTo("GENERATE");
    assertThat(state.kid()).isEqualTo("hmac-y.v1");
  }

  @Test
  void approvedChangesApplyOnceAndRetireOnlyAnOldVersion() {
    UUID gen = UUID.randomUUID();
    KeyChangeRequest generate =
        new KeyChangeRequest(KeyChangeRequest.Action.GENERATE, "hmac-x", "HMAC_X", null, "sec1", "sec2");
    assertThat(changes.apply(gen, generate).versions()).hasSize(1);
    assertThat(changes.apply(gen, generate).versions()).hasSize(1);

    KeyChangeRequest rotate = new KeyChangeRequest(KeyChangeRequest.Action.ROTATE, "hmac-x", null, null, "sec1", "sec2");
    assertThat(code(catchThrowable(() -> changes.apply(gen, rotate)))).isEqualTo("MW-VAL-018");
    assertThat(changes.apply(UUID.randomUUID(), rotate).versions()).hasSize(2);

    KeyChangeRequest retireActive =
        new KeyChangeRequest(KeyChangeRequest.Action.RETIRE, "hmac-x", null, 2, "sec1", "sec2");
    assertThat(code(catchThrowable(() -> changes.apply(UUID.randomUUID(), retireActive)))).isEqualTo("MW-VAL-018");
    KeyView retired = changes.apply(UUID.randomUUID(),
        new KeyChangeRequest(KeyChangeRequest.Action.RETIRE, "hmac-x", null, 1, "sec1", "sec2"));
    assertThat(retired.versions().getFirst().state()).isEqualTo("RETIRED");

    KeyChangeRequest own = new KeyChangeRequest(KeyChangeRequest.Action.ROTATE, "hmac-x", null, null, "sec1", "sec1");
    assertThat(code(catchThrowable(() -> changes.apply(UUID.randomUUID(), own)))).isEqualTo("MW-VAL-018");
  }
}

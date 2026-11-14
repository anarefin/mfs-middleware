package mfs.mw.crypto.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import mfs.mw.crypto.config.CryptoProperties;
import mfs.mw.crypto.exception.MwException;
import org.junit.jupiter.api.Test;

/**
 * What the PKCS#11 provider does without an HSM (the host test JVM has no PKCS#11 library): it fails
 * closed. Key generation and login against a real token: Pkcs11ProviderIT (in a container).
 */
class Pkcs11ProviderTest {

  static final String PIN = "fake-user-pin-not-real";

  static CryptoProperties props(String library, String pin) {
    return new CryptoProperties(
        "pkcs11", false, List.of(), Duration.ofSeconds(30), Duration.ofSeconds(10),
        new CryptoProperties.Pkcs11(library, 0, pin, 2, Duration.ofMillis(200), Duration.ofSeconds(2)));
  }

  @Test
  void configurationNamesTheLibraryAndSlotAndMakesKeysSensitiveAndUnextractable() {
    String cfg = Pkcs11Provider.configuration(props("/usr/lib/libp11-kit.so.0", PIN).pkcs11());
    assertThat(cfg)
        .contains("library = /usr/lib/libp11-kit.so.0\n", "slotListIndex = 0\n")
        .contains("attributes(*, CKO_PRIVATE_KEY, *) = {\n  CKA_SENSITIVE = true\n  CKA_EXTRACTABLE = false\n}")
        .contains("attributes(*, CKO_SECRET_KEY, *) = {\n  CKA_SENSITIVE = true\n  CKA_EXTRACTABLE = false\n}")
        .doesNotContain(PIN);
  }

  @Test
  void withoutTheLibraryEveryCallFailsClosedAndTheProbeSaysDown() {
    Pkcs11Provider p = new Pkcs11Provider(props("/nonexistent/libpkcs11.so", PIN));
    assertThat(p.probe()).isFalse();
    assertThatThrownBy(() -> p.generateKey("x.v1", KeyAlgorithm.EC_P256))
        .isInstanceOfSatisfying(MwException.class, e -> {
          assertThat(e.code()).isEqualTo("MW-SYS-010");
          assertThat(e.status().value()).isEqualTo(503);
          assertThat(e.retryable()).isTrue();
          assertThat(e.getMessage()).doesNotContain(PIN);
        });
    assertThatThrownBy(() -> p.publicKey("x.v1")).isInstanceOf(MwException.class);
  }

  @Test
  void withoutAUserPinItDoesNotTryToLogIn() {
    Pkcs11Provider p = new Pkcs11Provider(props("/bin/sh", ""));
    assertThatThrownBy(() -> p.destroy("x.v1"))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-SYS-010"));
  }

  @Test
  void capabilitiesAreThePkcs11OnesNotThePayShieldOnes() {
    assertThat(new Pkcs11Provider(props("/x", PIN)).capabilities())
        .contains(Capability.PIN_HMAC, Capability.JWS, Capability.TOKENISE, Capability.ENVELOPE, Capability.XMLDSIG)
        .doesNotContain(Capability.PIN_BLOCK, Capability.PVV);
  }

  @Test
  void thePinNeverShowsInThePropertiesToString() {
    assertThat(props("/x", PIN).toString()).doesNotContain(PIN).contains("userPin=****");
  }
}

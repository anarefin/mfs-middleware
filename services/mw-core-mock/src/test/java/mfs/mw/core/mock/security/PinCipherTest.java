package mfs.mw.core.mock.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import mfs.mw.core.mock.config.CoreMockProperties;
import mfs.mw.core.mock.dto.EncryptedPin;
import mfs.mw.core.mock.exception.MwException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** RSA-OAEP with SHA-256 (spec 6.3, M11): what a channel encrypts, core-mock decrypts. */
class PinCipherTest {

  static KeyPair pair;
  static PinKeyProvider keys;

  @BeforeAll
  static void generate() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
    g.initialize(2048);
    pair = g.generateKeyPair();
    keys = new PinKeyProvider(pin(pair));
  }

  static CoreMockProperties.Pin pin(KeyPair p) {
    return new CoreMockProperties.Pin(
        "core-pin-test-1", Base64.getEncoder().encodeToString(p.getPrivate().getEncoded()), 4);
  }

  @Test
  void decryptsWhatTheExportedPublicKeyEncrypted() throws Exception {
    EncryptedPin pin = TestPins.encrypt(keys.publicKeyPem(), keys.keyId(), "48271");

    assertThat(new PinCipher(keys).decrypt(pin)).contains("48271");
  }

  @Test
  void exportedPublicKeyIsThePairsPublicKey() {
    assertThat(keys.publicKeyPem())
        .startsWith("-----BEGIN PUBLIC KEY-----")
        .contains(Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(pair.getPublic().getEncoded()));
  }

  @Test
  void rejectsAnotherKeyId() throws Exception {
    EncryptedPin pin = TestPins.encrypt(keys.publicKeyPem(), "other-key", "48271");

    assertThat(new PinCipher(keys).decrypt(pin)).isEmpty();
  }

  @Test
  void rejectsGarbageAndPlainSha1Oaep() throws Exception {
    assertThat(new PinCipher(keys).decrypt(new EncryptedPin(keys.keyId(), "bm90LWEtcGlu"))).isEmpty();
    assertThat(new PinCipher(keys).decrypt(new EncryptedPin(keys.keyId(), "%%%"))).isEmpty();
  }

  @Test
  void acceptsPemEncodedPrivateKey() {
    String pem =
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(pair.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";

    PinKeyProvider fromPem = new PinKeyProvider(new CoreMockProperties.Pin("k", pem, 4));

    assertThat(fromPem.publicKeyPem()).isEqualTo(keys.publicKeyPem());
  }

  @Test
  void withoutAKeyTheServiceSaysSo() {
    PinKeyProvider none = new PinKeyProvider(new CoreMockProperties.Pin("k", "", 4));

    assertThatThrownBy(none::publicKeyPem).isInstanceOf(MwException.class);
  }
}

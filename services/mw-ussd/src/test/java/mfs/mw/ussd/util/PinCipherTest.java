package mfs.mw.ussd.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import mfs.mw.ussd.dto.EncryptedPin;
import org.junit.jupiter.api.Test;

class PinCipherTest {

  @Test
  void encryptsWithTheCorePublicKeySoOnlyThePrivateKeyRecoversThePin() throws Exception {
    PinCipher cipher = new PinCipher("core-pin-test", TestPinKeys.pem());

    EncryptedPin pin = cipher.encrypt("4826");

    assertThat(pin.keyId()).isEqualTo("core-pin-test");
    assertThat(pin.ciphertext()).doesNotContain("4826");
    assertThat(TestPinKeys.decrypt(pin)).isEqualTo("4826");
  }

  @Test
  void ciphertextIsRandomisedPerEncryption() {
    PinCipher cipher = new PinCipher("k", TestPinKeys.pem());

    assertThat(cipher.encrypt("4826").ciphertext()).isNotEqualTo(cipher.encrypt("4826").ciphertext());
  }

  @Test
  void refusesAKeyThatIsNotAnRsaPublicKey() {
    assertThatThrownBy(
            () -> new PinCipher("k", "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

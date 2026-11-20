package mfs.mw.app.gateway.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Linked account number at rest (ticket 117b): AES-GCM bound to the wallet, keyed lookup hash. */
class AccountCipherTest {

  private final AccountCipher cipher = new AccountCipher("test-only-linked-account-key-not-a-secret");

  @Test
  void theNumberRoundTripsAndIsNotInTheCiphertext() {
    String stored = cipher.encrypt("1234567890", "01711000001");

    assertThat(stored).startsWith("v1:").doesNotContain("1234567890").doesNotContain("7890");
    assertThat(cipher.decrypt(stored, "01711000001")).isEqualTo("1234567890");
  }

  @Test
  void twoEncryptionsDifferButTheLookupIsStable() {
    assertThat(cipher.encrypt("1234567890", "w")).isNotEqualTo(cipher.encrypt("1234567890", "w"));
    assertThat(cipher.lookup("dbbl", "1234567890")).isEqualTo(cipher.lookup("dbbl", "1234567890"))
        .isNotEqualTo(cipher.lookup("dbbl", "1234567891"))
        .doesNotContain("1234567890");
  }

  @Test
  void anotherWalletsRowDoesNotDecrypt() {
    String stored = cipher.encrypt("1234567890", "01711000001");

    assertThatThrownBy(() -> cipher.decrypt(stored, "01711000002")).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void anotherKeyDoesNotDecrypt() {
    String stored = cipher.encrypt("1234567890", "w");

    assertThatThrownBy(() -> new AccountCipher("another-test-only-key-not-a-secret-xx").decrypt(stored, "w"))
        .isInstanceOf(IllegalStateException.class);
  }
}

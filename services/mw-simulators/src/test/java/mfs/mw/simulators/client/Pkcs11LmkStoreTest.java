package mfs.mw.simulators.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.function.UnaryOperator;
import mfs.mw.simulators.config.PayShieldProperties;
import mfs.mw.simulators.util.PayShieldCrypto;
import org.junit.jupiter.api.Test;

/**
 * The paysim-token LMK store without a token: its encryption-only Feistel construction (the token
 * cannot decrypt over p11-kit) round trips with JCA AES as the round function, and with no library
 * the store fails closed. The token path itself runs in PayShieldSimulatorIT.
 */
class Pkcs11LmkStoreTest {

  static final byte[] KEY = new byte[32];

  static {
    new SecureRandom().nextBytes(KEY);
  }

  static final UnaryOperator<byte[]> AES = in -> PayShieldCrypto.aes(KEY, in, true);

  @Test
  void feistelRoundTripsAndChangesEveryBlock() {
    byte[] data = new byte[48];
    new SecureRandom().nextBytes(data);
    byte[] enc = Pkcs11LmkStore.feistel(data, true, AES);
    assertThat(enc).hasSize(48).isNotEqualTo(data);
    assertThat(Pkcs11LmkStore.feistel(enc, false, AES)).isEqualTo(data);
    // blocks are independent: the same block encrypts the same
    byte[] twice = new byte[32];
    System.arraycopy(data, 0, twice, 0, 16);
    System.arraycopy(data, 0, twice, 16, 16);
    byte[] e2 = Pkcs11LmkStore.feistel(twice, true, AES);
    assertThat(java.util.Arrays.copyOfRange(e2, 0, 16)).isEqualTo(java.util.Arrays.copyOfRange(e2, 16, 32));
  }

  @Test
  void withoutALibraryItFailsClosed() {
    var store = new Pkcs11LmkStore(new PayShieldProperties.Pkcs11("/no/such/lib.so", 0, "pin", "lmk"));
    assertThatThrownBy(() -> store.encrypt(new byte[16])).isInstanceOf(IllegalStateException.class);
  }
}

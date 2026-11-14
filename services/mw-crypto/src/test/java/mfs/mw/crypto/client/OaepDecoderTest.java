package mfs.mw.crypto.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.MGF1ParameterSpec;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import mfs.mw.crypto.exception.MwException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The OAEP half of the PIN block decrypt (the HSM does raw RSA): the block a channel builds today
 * (RSA-OAEP, SHA-256, MGF1-SHA-256, as the M11 {@code PinCipher}) decodes to the PIN; anything else is
 * {@code MW-VAL-014}.
 */
class OaepDecoderTest {

  static KeyPair rsa;

  @BeforeAll
  static void key() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
    g.initialize(3072);
    rsa = g.generateKeyPair();
  }

  static byte[] channelEncrypt(byte[] pin) throws Exception {
    Cipher c = Cipher.getInstance("RSA/ECB/OAEPPadding");
    c.init(Cipher.ENCRYPT_MODE, rsa.getPublic(),
        new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
    return c.doFinal(pin);
  }

  static byte[] rawDecrypt(byte[] ciphertext) throws Exception {
    Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
    c.init(Cipher.DECRYPT_MODE, rsa.getPrivate());
    return Pkcs11Provider.withLength(c.doFinal(ciphertext), 384);
  }

  @Test
  void decodesTheChannelsPinBlock() throws Exception {
    byte[] raw = rawDecrypt(channelEncrypt("40973".getBytes(StandardCharsets.US_ASCII)));
    assertThat(raw).hasSize(384);
    assertThat(new String(OaepDecoder.decode(raw), StandardCharsets.US_ASCII)).isEqualTo("40973");
  }

  @Test
  void aBlockWithBadPaddingIsABadPinBlock() throws Exception {
    byte[] raw = rawDecrypt(channelEncrypt("40973".getBytes(StandardCharsets.US_ASCII)));
    raw[100] ^= 1;
    assertThatThrownBy(() -> OaepDecoder.decode(raw))
        .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-VAL-014"));
    byte[] notOaep = new byte[384];
    notOaep[383] = 7;
    assertThatThrownBy(() -> OaepDecoder.decode(notOaep)).isInstanceOf(MwException.class);
    assertThatThrownBy(() -> OaepDecoder.decode(new byte[10])).isInstanceOf(MwException.class);
  }

  /** The KEK wrap's counter blocks, encrypted ECB as the HSM does, are exactly JCA AES-CTR. */
  @Test
  void wrapCounterBlocksAreJcaCtr() throws Exception {
    javax.crypto.SecretKey kek = new javax.crypto.spec.SecretKeySpec(new byte[32], "AES");
    byte[] iv = new byte[16];
    java.util.Arrays.fill(iv, 8, 16, (byte) 0xff);
    iv[15] = (byte) 0xfe;
    byte[] data = new byte[40];
    new java.security.SecureRandom().nextBytes(data);
    Cipher ecb = Cipher.getInstance("AES/ECB/NoPadding");
    ecb.init(Cipher.ENCRYPT_MODE, kek);
    byte[] stream = ecb.doFinal(Pkcs11Provider.counterBlocks(iv, 3));
    byte[] mine = new byte[40];
    for (int i = 0; i < 40; i++) {
      mine[i] = (byte) (data[i] ^ stream[i]);
    }
    Cipher ctr = Cipher.getInstance("AES/CTR/NoPadding");
    ctr.init(Cipher.ENCRYPT_MODE, kek, new javax.crypto.spec.IvParameterSpec(iv));
    assertThat(mine).isEqualTo(ctr.doFinal(data));
  }

  @Test
  void shortRawOutputIsLeftPadded() {
    byte[] out = {1, 2, 3};
    assertThat(Pkcs11Provider.withLength(out, 5)).containsExactly(0, 0, 1, 2, 3);
    assertThat(out).containsOnly(0);
  }
}

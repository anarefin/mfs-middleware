package mfs.mw.crypto.client;

import java.util.Arrays;
import mfs.mw.crypto.exception.CryptoErrors;
import org.bouncycastle.crypto.AsymmetricBlockCipher;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.encodings.OAEPEncoding;

/**
 * EME-OAEP decoding (RFC 8017 7.1.2; SHA-256, MGF1-SHA-256, empty label) of a block the HSM already
 * RSA-decrypted, with BouncyCastle's {@link OAEPEncoding} (constant time in the padding check).
 * SunPKCS11 has no OAEP cipher, so the HSM does the raw RSA step and this the padding. Every
 * failure is {@code MW-VAL-014}, whatever went wrong.
 */
final class OaepDecoder {

  private OaepDecoder() {}

  /** The message inside {@code block} ({@code k} bytes, the raw RSA output); the caller zeroes both. */
  static byte[] decode(byte[] block) {
    if (block.length < 2 * 32 + 2 || block[0] != 0) {
      throw CryptoErrors.badPinBlock();
    }
    // BouncyCastle's RSA engine hands OAEP the block without its leading zero byte (k - 1 bytes)
    byte[] em = Arrays.copyOfRange(block, 1, block.length);
    try {
      OAEPEncoding oaep = new OAEPEncoding(new Given(em), new SHA256Digest(), new SHA256Digest(), null);
      oaep.init(false, null);
      return oaep.processBlock(em, 0, em.length);
    } catch (InvalidCipherTextException | RuntimeException e) {
      throw CryptoErrors.badPinBlock();
    } finally {
      Arrays.fill(em, (byte) 0);
    }
  }

  /** An "RSA engine" whose decryption result is the block the HSM already computed. */
  private record Given(byte[] em) implements AsymmetricBlockCipher {
    @Override
    public void init(boolean forEncryption, CipherParameters param) {
      // nothing: the RSA step has run in the HSM
    }

    @Override
    public int getInputBlockSize() {
      return em.length + 1;
    }

    @Override
    public int getOutputBlockSize() {
      return em.length;
    }

    @Override
    public byte[] processBlock(byte[] in, int inOff, int len) {
      return em;
    }
  }
}

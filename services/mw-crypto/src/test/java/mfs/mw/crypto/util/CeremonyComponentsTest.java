package mfs.mw.crypto.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.KeyAgreement;
import org.junit.jupiter.api.Test;

/** The ceremony's component arithmetic (ticket 75): what the HSM derives equals the JDK's ECDH. */
class CeremonyComponentsTest {

  static final HexFormat HEX = HexFormat.of();
  /** P-256 group order n. */
  static final BigInteger N = new BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16);

  static byte[] scalar(BigInteger d) {
    byte[] raw = d.toByteArray();
    byte[] out = new byte[32];
    System.arraycopy(raw, Math.max(0, raw.length - 32), out, Math.max(0, 32 - raw.length), Math.min(32, raw.length));
    return out;
  }

  @Test
  void aComponentIsAScalarBelowTheGroupOrder() {
    assertThat(CeremonyComponents.valid(scalar(BigInteger.ONE))).isTrue();
    assertThat(CeremonyComponents.valid(scalar(N.subtract(BigInteger.ONE)))).isTrue();
    assertThat(CeremonyComponents.valid(new byte[32])).isFalse();
    assertThat(CeremonyComponents.valid(scalar(N))).isFalse();
    assertThat(CeremonyComponents.valid(new byte[31])).isFalse();
  }

  @Test
  void checkValueOfTheGeneratorIsShaOfItsCompressedPoint() throws Exception {
    // d = 1: d·G = G, compressed 03 || x(G) (y(G) is odd)
    byte[] g = HEX.parseHex("036B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296");
    String expected = HEX.withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(g), 0, 3);
    assertThat(CeremonyComponents.checkValue(scalar(BigInteger.ONE))).isEqualTo(expected).hasSize(6);
  }

  @Test
  void combinedKeyIsTheEcdhSecretOfTheTwoComponents() throws Exception {
    byte[] c1 = scalar(new BigInteger("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef", 16));
    byte[] c2 = scalar(new BigInteger("0fedcba9876543210fedcba9876543210fedcba9876543210fedcba987654321", 16));
    KeyAgreement ecdh = KeyAgreement.getInstance("ECDH");
    ecdh.init(CeremonyComponents.privateKey(c1));
    ecdh.doPhase(CeremonyComponents.publicKey(c2), true);
    byte[] jdk = ecdh.generateSecret();

    assertThat(CeremonyComponents.combined(c1, c2)).isEqualTo(jdk).hasSize(32);
    assertThat(CeremonyComponents.combined(c2, c1)).isEqualTo(jdk);
    assertThat(CeremonyComponents.keyCheckValueOf(jdk)).matches("[0-9A-F]{6}");
  }

  @Test
  void checkValuesCompareCaseInsensitively() {
    assertThat(CeremonyComponents.sameCheckValue("ab12CD", "AB12cd")).isTrue();
    assertThat(CeremonyComponents.sameCheckValue("ab12CD", "AB12CE")).isFalse();
    assertThat(CeremonyComponents.sameCheckValue(null, "AB12CE")).isFalse();
  }
}

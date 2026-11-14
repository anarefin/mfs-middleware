package mfs.mw.simulators.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * The payShield simulator's crypto primitives against published vectors: the DES worked example of
 * FIPS 46 tutorials (key 133457799BBCDFF1), the ISO 9564 format 0 example (PIN 1234, PAN
 * 4111111111111111), the Visa PVV transformation; and self-consistent round trips for format 1, 3, 4.
 */
class PayShieldCryptoTest {

  static final HexFormat HEX = HexFormat.of().withUpperCase();

  static byte[] hex(String s) {
    return HEX.parseHex(s);
  }

  @Test
  void tripleDesWithEqualHalvesIsSingleDesOfTheWorkedExample() {
    byte[] key = hex("133457799BBCDFF1133457799BBCDFF1");
    byte[] out = PayShieldCrypto.tdes(key, hex("0123456789ABCDEF"), true);
    assertThat(HEX.formatHex(out)).isEqualTo("85E813540F0AB405");
    assertThat(HEX.formatHex(PayShieldCrypto.tdes(key, out, false))).isEqualTo("0123456789ABCDEF");
  }

  @Test
  void checkValueIsTheEncryptedZeroBlock() {
    // DES of zeros under 0123456789ABCDEF = D5D44FF720683D0D (published KCV D5D44F)
    assertThat(PayShieldCrypto.checkValue(hex("0123456789ABCDEF0123456789ABCDEF"))).isEqualTo("D5D44F");
  }

  @Test
  void oddParity() {
    assertThat(PayShieldCrypto.oddParity(hex("0123456789ABCDEF"))).isTrue();
    assertThat(PayShieldCrypto.oddParity(hex("0023456789ABCDEF"))).isFalse();
    byte[] fixed = PayShieldCrypto.withOddParity(hex("0023456789ABCDEF"));
    assertThat(PayShieldCrypto.oddParity(fixed)).isTrue();
  }

  @Test
  void format0ClearBlockOfThePublishedExample() {
    assertThat(HEX.formatHex(PinBlocks.clearFormat0("1234", "4111111111111111"))).isEqualTo("041225EEEEEEEEEE");
    assertThat(PinBlocks.account12("4111111111111111")).isEqualTo("111111111111");
  }

  @Test
  void everyFormatRoundTrips() {
    byte[] tdes = hex("0123456789ABCDEFFEDCBA9876543210");
    byte[] aes = hex("000102030405060708090A0B0C0D0E0F");
    for (PinBlocks.Format f : PinBlocks.Format.values()) {
      byte[] key = f == PinBlocks.Format.ISO4 ? aes : tdes;
      byte[] block = PinBlocks.encipher(f, "48291", "4000001234562000", key);
      assertThat(block).hasSize(f == PinBlocks.Format.ISO4 ? 16 : 8);
      assertThat(PinBlocks.decipher(f, block, "4000001234562000", key)).as(f.name()).isEqualTo("48291");
    }
  }

  @Test
  void format0EncipheredUnderAKnownKey() {
    byte[] key = hex("0123456789ABCDEFFEDCBA9876543210");
    byte[] block = PinBlocks.encipher(PinBlocks.Format.ISO0, "1234", "4111111111111111", key);
    // independent: TDES of the clear block 041225EEEEEEEEEE (checked with the DES vector above)
    assertThat(block).isEqualTo(PayShieldCrypto.tdes(key, hex("041225EEEEEEEEEE"), true));
  }

  @Test
  void aWrongAccountOrKeyDoesNotGiveAValidPin() {
    byte[] key = hex("0123456789ABCDEFFEDCBA9876543210");
    byte[] block = PinBlocks.encipher(PinBlocks.Format.ISO3, "1234", "4111111111111111", key);
    assertThatThrownBy(() -> PinBlocks.decipher(PinBlocks.Format.ISO0, block, "4111111111111111", key))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void formatCodesAreThePayShieldOnes() {
    assertThat(PinBlocks.Format.fromCode("01")).isEqualTo(PinBlocks.Format.ISO0);
    assertThat(PinBlocks.Format.fromCode("05")).isEqualTo(PinBlocks.Format.ISO1);
    assertThat(PinBlocks.Format.fromCode("47")).isEqualTo(PinBlocks.Format.ISO3);
    assertThat(PinBlocks.Format.fromCode("48")).isEqualTo(PinBlocks.Format.ISO4);
  }

  @Test
  void visaPvvDecimalisesTheEncryptedTransformationSecurityParameter() {
    byte[] pvk = hex("0123456789ABCDEFFEDCBA9876543210");
    // TSP = 11 rightmost account digits without the check digit + PVKI + 4 PIN digits
    assertThat(PayShieldCrypto.tsp("4000001234562000", '1', "1234")).isEqualTo("0012345620011234");
    byte[] enc = PayShieldCrypto.tdes(pvk, hex("0012345620011234"), true);
    String expected = decimaliseByHand(HEX.formatHex(enc));
    assertThat(PayShieldCrypto.pvv(pvk, "4000001234562000", '1', "1234")).isEqualTo(expected).matches("[0-9]{4}");
    assertThat(PayShieldCrypto.decimalise("A1B2C3D4E5F6A7B8")).isEqualTo("1234");
    assertThat(PayShieldCrypto.decimalise("ABCDEFABCDEF9ABC")).isEqualTo("9012");
  }

  /** The Visa rule written out: digits 0-9 left to right, then A-F minus 10. */
  static String decimaliseByHand(String hex) {
    StringBuilder sb = new StringBuilder();
    for (char c : hex.toCharArray()) {
      if (c <= '9' && sb.length() < 4) {
        sb.append(c);
      }
    }
    for (char c : hex.toCharArray()) {
      if (c > '9' && sb.length() < 4) {
        sb.append((char) ('0' + (c - 'A')));
      }
    }
    return sb.toString();
  }
}

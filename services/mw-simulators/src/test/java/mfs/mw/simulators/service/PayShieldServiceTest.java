package mfs.mw.simulators.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;
import java.util.List;
import mfs.mw.simulators.client.InMemoryLmkStore;
import mfs.mw.simulators.dto.PayShieldScenario;
import mfs.mw.simulators.util.PayShieldCrypto;
import mfs.mw.simulators.util.PinBlocks;
import mfs.mw.simulators.util.PinBlocks.Format;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The payShield host commands (ticket 64) on an in-memory LMK: the answer of each command (after
 * the header) for known clear keys imported under the LMK, PIN blocks built independently with
 * {@link PinBlocks}, and the error codes 01, 10, 11, 15, 68.
 */
class PayShieldServiceTest {

  static final HexFormat HEX = HexFormat.of().withUpperCase();
  static final byte[] TPK = HEX.parseHex("0123456789ABCDEFFEDCBA9876543210");
  static final byte[] ZPK = HEX.parseHex("89ABCDEF01234567FEDCBA9876543210");
  static final byte[] PVK = HEX.parseHex("1A2B3C4D5E6F70801A2B3C4D5E6F7081");
  static final byte[] ZPK_AES = HEX.parseHex("000102030405060708090A0B0C0D0E0F");
  static final String PAN = "4000001234562000";
  static final String ACCOUNT = PinBlocks.account12(PAN);

  PayShieldScenarioStore scenario;
  PayShieldKeys keys;
  PayShieldService service;
  String tpk;
  String zpk;
  String pvk;
  String zpkAes;

  @BeforeEach
  void setUp() {
    scenario = new PayShieldScenarioStore();
    keys = new PayShieldKeys(new InMemoryLmkStore());
    service = new PayShieldService(keys, scenario, false);
    tpk = keys.wrap(PayShieldKeys.KeyType.TPK, TPK, 'U');
    zpk = keys.wrap(PayShieldKeys.KeyType.ZPK, ZPK, 'U');
    pvk = keys.wrap(PayShieldKeys.KeyType.PVK, PayShieldCrypto.withOddParity(PVK), 'U');
    zpkAes = keys.wrap(PayShieldKeys.KeyType.ZPK, ZPK_AES, 'S');
  }

  static String block(Format f, String pin, byte[] key) {
    return HEX.formatHex(PinBlocks.encipher(f, pin, PAN, key));
  }

  @Test
  void diagnosticsAnswerTheLmkCheckValueAndFirmware() {
    String r = service.handle("NC");
    assertThat(r).startsWith("ND00").hasSize(4 + 16 + 9);
    assertThat(r.substring(4, 20)).matches("[0-9A-F]{16}");
  }

  @Test
  void generateKeysUnderTheLmkAndExportUnderAZmk() {
    String r = service.handle("A00001U");
    assertThat(r).startsWith("A100").matches("A100U[0-9A-F]{32}[0-9A-F]{6}");
    String zmk = service.handle("A00000U").substring(4, 37);
    String exported = service.handle("A01002U" + zmk + "U");
    assertThat(exported).matches("A100U[0-9A-F]{32}U[0-9A-F]{32}[0-9A-F]{6}");
    // the key under the LMK and the one under the ZMK are the same key: same check value as the import
    String aes = service.handle("A00001S");
    assertThat(aes).matches("A100S[0-9A-F]{32}[0-9A-F]{6}");
  }

  @Test
  void translateTpkToZpkFormat0To0() {
    String r = service.handle("CA" + tpk + zpk + "12" + block(Format.ISO0, "1234", TPK) + "0101" + ACCOUNT);
    assertThat(r).startsWith("CB0004");
    String out = r.substring(6, 22);
    assertThat(r.substring(22)).isEqualTo("01");
    assertThat(PinBlocks.decipher(Format.ISO0, HEX.parseHex(out), PAN, ZPK)).isEqualTo("1234");
  }

  @Test
  void translateTpkFormat0ToAnAesZpkFormat4() {
    String r = service.handle("CA" + tpk + zpkAes + "12" + block(Format.ISO0, "48291", TPK) + "0148" + "16" + PAN);
    assertThat(r).startsWith("CB0005").endsWith("48");
    String out = r.substring(6, 38);
    assertThat(PinBlocks.decipher(Format.ISO4, HEX.parseHex(out), PAN, ZPK_AES)).isEqualTo("48291");
  }

  @Test
  void translateZpkToZpkFormat3To1() {
    String zpk2 = keys.wrap(PayShieldKeys.KeyType.ZPK, TPK, 'U');
    String r = new PayShieldService(keys, scenario, true)
        .handle("CC" + zpk + zpk2 + "12" + block(Format.ISO3, "987654", ZPK) + "4705" + ACCOUNT);
    assertThat(r).startsWith("CD0006").endsWith("05");
    assertThat(PinBlocks.decipher(Format.ISO1, HEX.parseHex(r.substring(6, 22)), PAN, TPK)).isEqualTo("987654");
  }

  @Test
  void pvvGeneratedFromThePinUnderLmkVerifiesThroughTpkAndZpk() {
    String jc = service.handle("JC" + tpk + block(Format.ISO0, "1234", TPK) + "01" + ACCOUNT);
    assertThat(jc).startsWith("JD00");
    String pinLmk = jc.substring(4);
    String dh = service.handle("DG" + pvk + pinLmk + ACCOUNT + "1");
    assertThat(dh).startsWith("DH00");
    String pvv = dh.substring(4);
    // independent: the Visa PVV of the clear PIN under the clear PVK
    assertThat(pvv).isEqualTo(PayShieldCrypto.pvv(PayShieldCrypto.withOddParity(PVK), PAN, '1', "1234"));

    assertThat(service.handle("DC" + tpk + pvk + block(Format.ISO0, "1234", TPK) + "01" + ACCOUNT + "1" + pvv))
        .isEqualTo("DD00");
    assertThat(service.handle("DC" + tpk + pvk + block(Format.ISO0, "1235", TPK) + "01" + ACCOUNT + "1" + pvv))
        .isEqualTo("DD01");
    String je = service.handle("JE" + zpk + block(Format.ISO3, "1234", ZPK) + "47" + ACCOUNT);
    assertThat(je).startsWith("JF00");
    assertThat(service.handle("EC" + zpk + pvk + block(Format.ISO3, "1234", ZPK) + "47" + ACCOUNT + "1" + pvv))
        .isEqualTo("ED00");
    assertThat(service.handle("EC" + zpk + pvk + block(Format.ISO3, "9999", ZPK) + "47" + ACCOUNT + "1" + pvv))
        .isEqualTo("ED01");
  }

  @Test
  void dataEncryptAndDecryptRoundTrip() {
    String zek = service.handle("A0000AU").substring(4, 37);
    String data = "00112233445566778899AABBCCDDEEFF";
    String m1 = service.handle("M0" + "00" + "1" + "1" + "00A" + zek + "0010" + data);
    assertThat(m1).startsWith("M1000010");
    String enc = m1.substring(8);
    assertThat(enc).hasSize(32).isNotEqualTo(data);
    assertThat(service.handle("M2" + "00" + "1" + "1" + "00A" + zek + "0010" + enc)).isEqualTo("M3000010" + data);
    String iv = "0102030405060708";
    String cbc = service.handle("M0" + "01" + "1" + "1" + "00A" + zek + iv + "0010" + data);
    assertThat(cbc).startsWith("M100" + "0010");
    assertThat(service.handle("M2" + "01" + "1" + "1" + "00A" + zek + iv + "0010" + cbc.substring(8)))
        .isEqualTo("M3000010" + data);
  }

  @Test
  void aKeyOfAnotherTypeIsAParityErrorOfItsField() {
    // a ZPK given as the TPK: unwrapped under the TPK variant it loses its parity
    assertThat(service.handle("CA" + zpk + zpk + "12" + block(Format.ISO0, "1234", ZPK) + "0101" + ACCOUNT))
        .isEqualTo("CB10");
    assertThat(service.handle("CA" + tpk + tpk + "12" + block(Format.ISO0, "1234", TPK) + "0101" + ACCOUNT))
        .isEqualTo("CB11");
  }

  @Test
  void translationOutOfAPanBoundFormatIntoIso1IsDisallowed() {
    // payShield security setting "allow translation to non-PAN formats" off (default): error 69
    assertThat(service.handle("CA" + tpk + zpk + "12" + block(Format.ISO0, "1234", TPK) + "0105" + ACCOUNT))
        .isEqualTo("CB69");
    assertThat(service.handle("CC" + zpk + zpk + "12" + block(Format.ISO3, "1234", ZPK) + "4705" + ACCOUNT))
        .isEqualTo("CD69");
    // ISO 1 to ISO 1 and ISO 1 into a PAN-bound format stay allowed
    assertThat(service.handle("CA" + tpk + zpk + "12" + block(Format.ISO1, "1234", TPK) + "0505" + ACCOUNT))
        .startsWith("CB00");
    assertThat(service.handle("CA" + tpk + zpk + "12" + block(Format.ISO1, "1234", TPK) + "0501" + ACCOUNT))
        .startsWith("CB00");
    var permissive = new PayShieldService(keys, scenario, true);
    assertThat(permissive.handle("CA" + tpk + zpk + "12" + block(Format.ISO0, "1234", TPK) + "0105" + ACCOUNT))
        .startsWith("CB00");
  }

  @Test
  void invalidInputIs15() {
    assertThat(service.handle("CA" + tpk + zpk + "12" + "0000000000000000" + "0101" + ACCOUNT)).isEqualTo("CB15");
    assertThat(service.handle("CA" + tpk)).isEqualTo("CB15");
    assertThat(service.handle("CA" + tpk + zpk + "12" + block(Format.ISO0, "1234", TPK) + "0199" + ACCOUNT))
        .isEqualTo("CB15");
    assertThat(service.handle("A09001U")).isEqualTo("A115");
  }

  @Test
  void scenarioForcesAnErrorCodeOrDisablesCommands() {
    scenario.set(new PayShieldScenario(null, "68", null, List.of("DG")));
    assertThat(service.handle("DG" + pvk + "0".repeat(32) + ACCOUNT + "1")).isEqualTo("DH68");
    assertThat(service.handle("NC")).startsWith("ND00");
    scenario.set(new PayShieldScenario(null, "15", null, null));
    assertThat(service.handle("NC")).isEqualTo("ND15");
    scenario.reset();
    assertThat(service.handle("NC")).startsWith("ND00");
  }

  @Test
  void unknownCommandIsDisabled() {
    assertThat(service.handle("XY")).isEqualTo("XZ68");
  }
}

package mfs.mw.simulators.service;

import java.util.Arrays;
import java.util.HexFormat;
import mfs.mw.simulators.service.PayShieldKeys.Key;
import mfs.mw.simulators.service.PayShieldKeys.KeyType;
import mfs.mw.simulators.service.PayShieldKeys.ParityException;
import mfs.mw.simulators.util.PayShieldCrypto;
import mfs.mw.simulators.util.PinBlocks;
import mfs.mw.simulators.util.PinBlocks.Format;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The Thales payShield 10K host commands of the simulator (ticket 64, spec 18.8, D23), one message
 * body (after the header) in, the answer body out: the response code (the command code with its
 * second letter advanced: {@code CA -> CB}), the 2-digit error code and the response fields. Error
 * codes: {@code 00} OK, {@code 01} PIN verification failure, {@code 10} / {@code 11} parity error of
 * the first / second key field, {@code 15} invalid input, {@code 68} command disabled (also an
 * unknown command), {@code 69} a translation from a PAN-bound format into ISO 1 while that is not
 * allowed (the default). Field layouts (CLAUDE.md "payShield simulator"): keys are 33-character blobs
 * under the LMK ({@link PayShieldKeys}); a PIN block is 16 hex digits under a {@code U} key and 32
 * under an {@code S} (AES) key; the account field is 12 digits (the rightmost 12 without the check
 * digit), or a 2-digit length and the whole PAN when a PIN block of ISO format 4 is involved.
 */
@Service
public class PayShieldService {

  static final String FIRMWARE = "1500-0000";
  private static final HexFormat HEX = HexFormat.of().withUpperCase();

  private final PayShieldKeys keys;
  private final boolean allowNonPanTranslation;

  /**
   * @param allowNonPanTranslation the payShield security setting "allow translation to non-PAN
   *     formats": off (the default), {@code CA} / {@code CC} from a PAN-bound format (ISO 0, 3, 4)
   *     into ISO 1 answer error {@code 69} (PIN block format not permitted)
   */
  public PayShieldService(PayShieldKeys keys,
      @Value("${sim.payshield.allow-non-pan-translation:false}") boolean allowNonPanTranslation) {
    this.keys = keys;
    this.allowNonPanTranslation = allowNonPanTranslation;
  }

  /** A failed command: its error code. */
  private static final class CommandError extends RuntimeException {
    final String code;

    CommandError(String code) {
      super(code, null, false, false);
      this.code = code;
    }
  }

  /** The answer to the message body {@code body} (command code and fields). */
  public String handle(String body) {
    String command = body.length() >= 2 ? body.substring(0, 2) : body;
    String response = responseCode(command);
    Fields f = new Fields(body, 2);
    try {
      String out =
          switch (command) {
            case "NC" -> keys.lmkCheckValue() + FIRMWARE;
            case "A0" -> generateKey(f);
            case "CA" -> translate(f, KeyType.TPK);
            case "CC" -> translate(f, KeyType.ZPK);
            case "JC" -> pinToLmk(f, KeyType.TPK);
            case "JE" -> pinToLmk(f, KeyType.ZPK);
            case "DG" -> generatePvv(f);
            case "DC" -> verifyPvv(f, KeyType.TPK);
            case "EC" -> verifyPvv(f, KeyType.ZPK);
            case "M0" -> data(f, true);
            case "M2" -> data(f, false);
            default -> throw new CommandError("68");
          };
      return response + "00" + out;
    } catch (CommandError e) {
      return response + e.code;
    } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
      return response + "15";
    }
  }

  /** The answer refusing the message body {@code body} with the payShield error {@code code} (mock control). */
  public String refuse(String body, String code) {
    return responseCode(body.length() >= 2 ? body.substring(0, 2) : body) + code;
  }

  /** The response code of {@code body}'s command alone, no error code (a cut-short answer, mock control). */
  public String cutShort(String body) {
    return responseCode(body.length() >= 2 ? body.substring(0, 2) : body);
  }

  /**
   * Test helper of the control API (the terminal or switch side, which shares the key): {@code pin}
   * as a format {@code format} PIN block under the key {@code blob} of {@code type} (TPK or ZPK) for
   * {@code pan}, hex. IllegalArgumentException for bad input or a key of another type.
   */
  public String pinBlock(KeyType type, String blob, Format format, String pin, String pan) {
    if (type != KeyType.TPK && type != KeyType.ZPK) {
      throw new IllegalArgumentException("TPK or ZPK only");
    }
    Key k;
    try {
      k = keys.unwrap(type, blob);
    } catch (ParityException e) {
      throw new IllegalArgumentException("key parity error", e);
    }
    try {
      checkFormat(format, k);
      return HEX.formatHex(PinBlocks.encipher(format, pin, pan, k.clear()));
    } finally {
      Arrays.fill(k.clear(), (byte) 0);
    }
  }

  /** {@code CA -> CB}, {@code NC -> ND}. */
  static String responseCode(String command) {
    if (command.length() != 2) {
      return "ZZ";
    }
    char second = command.charAt(1);
    char next = second == 'Z' ? 'Z' : second == '9' ? '0' : (char) (second + 1);
    return "" + command.charAt(0) + next;
  }

  /** A0: mode (0 generate, 1 and export under a ZMK), key type, scheme [, ZMK, export scheme]. */
  private String generateKey(Fields f) {
    char mode = f.chr();
    KeyType type = KeyType.fromCode(f.take(3));
    char scheme = f.chr();
    if (mode != '0' && mode != '1') {
      throw new IllegalArgumentException("mode");
    }
    byte[] clear = PayShieldKeys.random(scheme);
    try {
      String blob = keys.wrap(type, clear, scheme);
      String exported = "";
      if (mode == '1') {
        Key zmk = key(KeyType.ZMK, f.take(33), "10");
        char exportScheme = f.chr();
        try {
          if (zmk.aes() || scheme != 'U' || exportScheme != 'U') {
            throw new IllegalArgumentException("export scheme");
          }
          exported = exportScheme + HEX.formatHex(PayShieldCrypto.tdes(zmk.clear(), clear, true));
        } finally {
          Arrays.fill(zmk.clear(), (byte) 0);
        }
      }
      f.end();
      String kcv = scheme == 'U' ? PayShieldCrypto.checkValue(clear) : PayShieldCrypto.checkValueAes(clear);
      return blob + exported + kcv;
    } finally {
      Arrays.fill(clear, (byte) 0);
    }
  }

  /** CA (TPK -> ZPK) / CC (ZPK -> ZPK): source key, destination ZPK, max PIN length, block, formats, account. */
  private String translate(Fields f, KeyType sourceType) {
    Key source = key(sourceType, f.take(33), "10");
    Key dest = key(KeyType.ZPK, f.take(33), "11");
    try {
      int max = Integer.parseInt(f.digits(2));
      byte[] block = HEX.parseHex(f.take(source.aes() ? 32 : 16));
      Format from = Format.fromCode(f.take(2));
      Format to = Format.fromCode(f.take(2));
      checkFormat(from, source);
      checkFormat(to, dest);
      if (!allowNonPanTranslation && to == Format.ISO1 && from != Format.ISO1) {
        throw new CommandError("69");
      }
      String pan = account(f, from, to);
      f.end();
      String pin = PinBlocks.decipher(from, block, pan, source.clear());
      if (pin.length() > max) {
        throw new IllegalArgumentException("PIN longer than the maximum");
      }
      byte[] out = PinBlocks.encipher(to, pin, pan, dest.clear());
      return String.format("%02d", pin.length()) + HEX.formatHex(out) + to.code();
    } finally {
      Arrays.fill(source.clear(), (byte) 0);
      Arrays.fill(dest.clear(), (byte) 0);
    }
  }

  /** JC (TPK) / JE (ZPK): key, block, format, account -> the PIN under the LMK. */
  private String pinToLmk(Fields f, KeyType type) {
    Key k = key(type, f.take(33), "10");
    try {
      byte[] block = HEX.parseHex(f.take(k.aes() ? 32 : 16));
      Format format = Format.fromCode(f.take(2));
      checkFormat(format, k);
      String pan = account(f, format, format);
      f.end();
      return keys.pinUnderLmk(PinBlocks.decipher(format, block, pan, k.clear()));
    } finally {
      Arrays.fill(k.clear(), (byte) 0);
    }
  }

  /** DG: PVK pair, PIN under the LMK, account (12N), PVKI -> PVV. */
  private String generatePvv(Fields f) {
    Key pvk = key(KeyType.PVK, f.take(33), "10");
    try {
      if (pvk.aes()) {
        throw new IllegalArgumentException("PVK must be triple DES");
      }
      String pin = keys.pinFromLmk(f.take(32));
      String pan = f.digits(12) + "0";
      char pvki = f.digits(1).charAt(0);
      f.end();
      return PayShieldCrypto.pvv(pvk.clear(), pan, pvki, pin);
    } finally {
      Arrays.fill(pvk.clear(), (byte) 0);
    }
  }

  /** DC (TPK) / EC (ZPK): key, PVK pair, block, format, account, PVKI, PVV -> 00 or 01. */
  private String verifyPvv(Fields f, KeyType type) {
    Key k = key(type, f.take(33), "10");
    Key pvk = key(KeyType.PVK, f.take(33), "11");
    try {
      if (pvk.aes()) {
        throw new IllegalArgumentException("PVK must be triple DES");
      }
      byte[] block = HEX.parseHex(f.take(k.aes() ? 32 : 16));
      Format format = Format.fromCode(f.take(2));
      checkFormat(format, k);
      String pan = account(f, format, format);
      char pvki = f.digits(1).charAt(0);
      String pvv = f.digits(4);
      f.end();
      String pin = PinBlocks.decipher(format, block, pan, k.clear());
      String expected = PayShieldCrypto.pvv(pvk.clear(), PinBlocks.account12(pan) + "0", pvki, pin);
      if (!java.security.MessageDigest.isEqual(expected.getBytes(), pvv.getBytes())) {
        throw new CommandError("01");
      }
      return "";
    } finally {
      Arrays.fill(k.clear(), (byte) 0);
      Arrays.fill(pvk.clear(), (byte) 0);
    }
  }

  /**
   * M0 / M2: mode (00 ECB, 01 CBC), input and output format (1 = hex only), key type (00A ZEK), key,
   * IV (CBC only: 16 hex for U, 32 for S), message length (4 hex, bytes), message (hex) -> length and
   * the result (hex).
   */
  private String data(Fields f, boolean encrypt) {
    String mode = f.digits(2);
    if (f.chr() != '1' || f.chr() != '1') {
      throw new IllegalArgumentException("only hex input and output");
    }
    KeyType type = KeyType.fromCode(f.take(3));
    if (type != KeyType.ZEK) {
      throw new IllegalArgumentException("key type");
    }
    Key k = key(type, f.take(33), "10");
    try {
      int blockSize = k.aes() ? 16 : 8;
      byte[] iv = switch (mode) {
        case "00" -> null;
        case "01" -> HEX.parseHex(f.take(blockSize * 2));
        default -> throw new IllegalArgumentException("mode");
      };
      int len = Integer.parseInt(f.take(4), 16);
      byte[] msg = HEX.parseHex(f.take(len * 2));
      f.end();
      if (len == 0 || len % blockSize != 0) {
        throw new IllegalArgumentException("length not a whole number of blocks");
      }
      byte[] out = new byte[len];
      byte[] chain = iv;
      for (int i = 0; i < len; i += blockSize) {
        byte[] in = Arrays.copyOfRange(msg, i, i + blockSize);
        byte[] res;
        if (encrypt) {
          res = cipher(k, chain == null ? in : PayShieldCrypto.xor(in, chain), true);
          chain = chain == null ? null : res;
        } else {
          byte[] plain = cipher(k, in, false);
          res = chain == null ? plain : PayShieldCrypto.xor(plain, chain);
          chain = chain == null ? null : in;
        }
        System.arraycopy(res, 0, out, i, blockSize);
      }
      return String.format("%04X", len) + HEX.formatHex(out);
    } finally {
      Arrays.fill(k.clear(), (byte) 0);
    }
  }

  private static byte[] cipher(Key k, byte[] in, boolean encrypt) {
    return k.aes() ? PayShieldCrypto.aes(k.clear(), in, encrypt) : PayShieldCrypto.tdes(k.clear(), in, encrypt);
  }

  private Key key(KeyType type, String blob, String parityCode) {
    try {
      return keys.unwrap(type, blob);
    } catch (ParityException e) {
      throw new CommandError(parityCode);
    }
  }

  /** Format 4 needs an AES key, the others a triple DES key. */
  private static void checkFormat(Format format, Key key) {
    if ((format == Format.ISO4) != key.aes()) {
      throw new IllegalArgumentException("PIN block format does not fit the key");
    }
  }

  /** The account field as a PAN {@link PinBlocks} reads (12 digits get a dummy check digit). */
  private static String account(Fields f, Format a, Format b) {
    if (a == Format.ISO4 || b == Format.ISO4) {
      int n = Integer.parseInt(f.digits(2));
      if (n < 12 || n > 19) {
        throw new IllegalArgumentException("PAN length");
      }
      return f.digits(n);
    }
    return f.digits(12) + "0";
  }

  /** A cursor over the fixed-length fields of a message body. */
  private static final class Fields {
    private final String body;
    private int pos;

    Fields(String body, int pos) {
      this.body = body;
      this.pos = pos;
    }

    String take(int n) {
      String s = body.substring(pos, pos + n);
      pos += n;
      return s;
    }

    char chr() {
      return take(1).charAt(0);
    }

    String digits(int n) {
      String s = take(n);
      if (!s.chars().allMatch(Character::isDigit)) {
        throw new IllegalArgumentException("digits expected");
      }
      return s;
    }

    void end() {
      if (pos != body.length()) {
        throw new IllegalArgumentException("trailing data");
      }
    }
  }
}

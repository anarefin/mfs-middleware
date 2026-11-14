package mfs.mw.crypto.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import mfs.mw.crypto.dto.DerivePinRequest;
import mfs.mw.crypto.dto.DerivePinResponse;
import mfs.mw.crypto.dto.EncryptedPin;
import mfs.mw.crypto.dto.PinPolicyInput;
import mfs.mw.crypto.dto.VerifyPinRequest;
import mfs.mw.crypto.dto.VerifyPinResponse;
import mfs.mw.crypto.entity.KeyState;
import mfs.mw.crypto.exception.MwException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PIN verify and derive above the provider SPI (spec 18.7, 18.8): known answer, match and no match,
 * re-hash after a rotation of the PIN HMAC key, the weak-PIN policy, bad blocks and zeroing.
 */
class PinServiceTest {

  static final String WALLET = "01712345678";
  /** HMAC-SHA256, key 32 x 0x0b, over "01712345678" 0x00 "40973" (computed with Python's hmac module). */
  static final String KAT_40973 = "88fmLPzqr30W7eoorPdD/qbab8z7hyoKxG0pQDEItlk=";

  OperationFixture f;
  PinService pins;

  @BeforeEach
  void setUp() {
    f = new OperationFixture()
        .key("pin-transport", 1, "PIN_TRANSPORT", KeyState.ACTIVE)
        .key("pin-hmac", 1, "PIN_HMAC", KeyState.ACTIVE)
        .key("jwt-sign", 1, "JWT_SIGN", KeyState.ACTIVE);
    f.hsm.putHmacKey("pin-hmac.v1", filled(0x0b));
    pins = new PinService(f.keys, f.callers);
  }

  static byte[] filled(int b) {
    byte[] k = new byte[32];
    java.util.Arrays.fill(k, (byte) b);
    return k;
  }

  static String code(Throwable e) {
    return ((MwException) e).code();
  }

  DerivePinResponse derive(String pin, PinPolicyInput policy) {
    return pins.derive(new DerivePinRequest(WALLET, f.encrypt("pin-transport", pin), policy));
  }

  VerifyPinResponse verify(EncryptedPin block, String stored, String kid) {
    return pins.verify(new VerifyPinRequest(WALLET, block, stored, kid));
  }

  @Test
  void deriveIsTheKnownHmacOverWalletIdNulAndPin() {
    DerivePinResponse r = derive("40973", null);
    assertThat(r.weak()).isFalse();
    assertThat(r.hmac()).isEqualTo(KAT_40973);
    assertThat(r.hmacKeyId()).isEqualTo("pin-hmac.v1");
  }

  @Test
  void verifyMatchesTheRightPinOnlyAndAWrongPinIsNoError() {
    assertThat(verify(f.encrypt("pin-transport", "40973"), KAT_40973, "pin-hmac.v1"))
        .isEqualTo(new VerifyPinResponse(true, null));
    assertThat(verify(f.encrypt("pin-transport", "40974"), KAT_40973, "pin-hmac.v1"))
        .isEqualTo(new VerifyPinResponse(false, null));
    // the walletId is part of the HMAC: the same PIN of another wallet does not match
    assertThat(pins.verify(new VerifyPinRequest("01712345679", f.encrypt("pin-transport", "40973"), KAT_40973,
        "pin-hmac.v1")).match()).isFalse();
  }

  @Test
  void aMatchUnderTheVerifyOnlyVersionReturnsTheRehashUnderTheActiveOne() {
    OperationFixture rotated = new OperationFixture()
        .key("pin-transport", 1, "PIN_TRANSPORT", KeyState.VERIFY_ONLY)
        .key("pin-transport", 2, "PIN_TRANSPORT", KeyState.ACTIVE)
        .key("pin-hmac", 1, "PIN_HMAC", KeyState.VERIFY_ONLY)
        .key("pin-hmac", 2, "PIN_HMAC", KeyState.ACTIVE);
    rotated.hsm.putHmacKey("pin-hmac.v1", filled(0x0b));
    PinService service = new PinService(rotated.keys, rotated.callers);

    // a block for the previous transport version still decrypts
    VerifyPinResponse r = service.verify(
        new VerifyPinRequest(WALLET, rotated.encrypt("pin-transport.v1", "40973"), KAT_40973, "pin-hmac.v1"));
    assertThat(r.match()).isTrue();
    assertThat(r.rehash().hmacKeyId()).isEqualTo("pin-hmac.v2");
    assertThat(r.rehash().hmac()).isNotEqualTo(KAT_40973);
    DerivePinResponse fresh = service.derive(
        new DerivePinRequest(WALLET, rotated.encrypt("pin-transport", "40973"), null));
    assertThat(r.rehash().hmac()).isEqualTo(fresh.hmac());
    // the re-hashed value verifies under v2 without another re-hash
    assertThat(service.verify(new VerifyPinRequest(WALLET, rotated.encrypt("pin-transport", "40973"),
        r.rehash().hmac(), "pin-hmac.v2"))).isEqualTo(new VerifyPinResponse(true, null));
    // no re-hash on a wrong PIN
    assertThat(service.verify(new VerifyPinRequest(WALLET, rotated.encrypt("pin-transport", "11112"), KAT_40973,
        "pin-hmac.v1"))).isEqualTo(new VerifyPinResponse(false, null));
  }

  @Test
  void aRetiredHmacVersionOrAKeyOfAnotherPurposeIsRefused() {
    OperationFixture retired = new OperationFixture()
        .key("pin-transport", 1, "PIN_TRANSPORT", KeyState.ACTIVE)
        .key("pin-hmac", 1, "PIN_HMAC", KeyState.RETIRED)
        .key("pin-hmac", 2, "PIN_HMAC", KeyState.ACTIVE);
    PinService service = new PinService(retired.keys, retired.callers);
    assertThatThrownBy(() -> service.verify(new VerifyPinRequest(WALLET, retired.encrypt("pin-transport", "40973"),
        KAT_40973, "pin-hmac.v1"))).satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-015"));
    assertThatThrownBy(() -> verify(f.encrypt("pin-transport", "40973"), KAT_40973, "jwt-sign.v1"))
        .satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-015"));
    assertThatThrownBy(() -> verify(new EncryptedPin("jwt-sign", "AAAA"), KAT_40973, "pin-hmac.v1"))
        .satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-015"));
    assertThatThrownBy(() -> verify(f.encrypt("pin-transport", "40973"), KAT_40973, "pin-hmac.v9"))
        .satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-010"));
  }

  @Test
  void theClearPinBufferIsZeroedAfterEveryCall() {
    verify(f.encrypt("pin-transport", "40973"), KAT_40973, "pin-hmac.v1");
    assertThat(f.hsm.lastDecrypted()).hasSize(5).containsOnly(0);

    derive("40973", null);
    assertThat(f.hsm.lastDecrypted()).hasSize(5).containsOnly(0);

    derive("12345", null);
    assertThat(f.hsm.lastDecrypted()).hasSize(5).containsOnly(0);

    assertThatThrownBy(() -> derive("1234", null)).isInstanceOf(MwException.class);
    assertThat(f.hsm.lastDecrypted()).hasSize(4).containsOnly(0);
  }

  @Test
  void aBlockWithoutExactlyFiveAsciiDigitsIsABadBlock() {
    for (String bad : new String[] {"1234", "123456", "4097a", "4097 ", "٤٠٩٧٣"}) {
      EncryptedPin block = new EncryptedPin("pin-transport",
          java.util.Base64.getEncoder().encodeToString(f.oaep("pin-transport", bad.getBytes(StandardCharsets.UTF_8))));
      assertThatThrownBy(() -> verify(block, KAT_40973, "pin-hmac.v1"))
          .as(bad).satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-014"));
    }
    EncryptedPin garbage = new EncryptedPin("pin-transport", java.util.Base64.getEncoder().encodeToString(new byte[384]));
    assertThatThrownBy(() -> verify(garbage, KAT_40973, "pin-hmac.v1"))
        .satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-014"));
    assertThatThrownBy(() -> verify(new EncryptedPin("pin-transport", "abcde"), KAT_40973, "pin-hmac.v1"))
        .satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-001"));
  }

  @Test
  void aWeakPinIsAnsweredWithItsRuleAndNoHmac() {
    assertThat(derive("12345", null)).isEqualTo(DerivePinResponse.weak("SEQUENCE"));
    assertThat(derive("71234", new PinPolicyInput(WALLET, null, null, null)))
        .isEqualTo(DerivePinResponse.weak("MSISDN_DIGITS"));
    assertThat(derive("19905", new PinPolicyInput(null, 1990, null, null)))
        .isEqualTo(DerivePinResponse.weak("BIRTH_YEAR"));
    assertThat(derive("40973", new PinPolicyInput(null, null, KAT_40973, "pin-hmac.v1")))
        .isEqualTo(DerivePinResponse.weak("LAST_PIN"));
    assertThat(derive("40974", new PinPolicyInput(WALLET, 1990, KAT_40973, "pin-hmac.v1")).weak()).isFalse();
    assertThatThrownBy(() -> derive("40974", new PinPolicyInput(null, null, KAT_40973, null)))
        .satisfies(e -> assertThat(code(e)).isEqualTo("MW-VAL-001"));
  }

  @Test
  void anUnreachableHsmFailsClosed() {
    EncryptedPin block = f.encrypt("pin-transport", "40973");
    f.hsm.down(true);
    assertThatThrownBy(() -> verify(block, KAT_40973, "pin-hmac.v1"))
        .satisfies(e -> {
          assertThat(code(e)).isEqualTo("MW-SYS-010");
          assertThat(((MwException) e).retryable()).isTrue();
        });
  }
}

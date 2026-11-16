package mfs.mw.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import mfs.mw.auth.util.MaskingPolicy;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/**
 * Dev only: a wallet's authentication state as the test console shows it ({@code auth-test.v1}
 * TestAccount; {@code GET /test/v1/accounts/{walletId}}). Never a PIN, an HMAC or an OTP.
 *
 * @param walletStatus the core's status from the projection (ACTIVE, BLOCKED, HOLD, LOCKED)
 * @param pinSet the wallet has a credential (mw-auth's PIN custody)
 * @param locked a PIN lock or an ADMIN lock is in force now
 * @param lockedUntil the end of the PIN lock in force; absent when not locked or locked until a reset/unlock
 * @param failedPinAttempts the wrong PINs counted at the current lockout level
 * @param pinLock the PIN lockout row (also kept after its end as the level history); absent when none
 * @param adminLocked an ops lock ({@code auth.wallet-lock.v1} LOCK) is in force
 * @param deviceOtp the device OTP (BIND, LOGIN, RESET) and its send window; absent when none
 * @param checkoutOtpSends the checkout OTPs counted in the wallet's current quota window
 * @param devices every device bound to this wallet now or before, newest binding first
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TestAccount(
    @Sensitive(SensitiveType.MSISDN) String walletId,
    String walletStatus,
    boolean pinSet,
    boolean locked,
    Instant lockedUntil,
    int failedPinAttempts,
    PinLock pinLock,
    boolean adminLocked,
    DeviceOtp deviceOtp,
    int checkoutOtpSends,
    List<Device> devices) {

  /** The PIN lockout: level 1 or 2 end at {@code until}, level 3 has none (until a reset or unlock). */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record PinLock(int level, Instant until, boolean active) {}

  /** The device OTP: its purpose, sends of the window, checks used, expiry, and whether it can still be used. */
  public record DeviceOtp(String purpose, int sends, int attempts, Instant expiresAt, boolean usable) {}

  /** A device row: BOUND, or UNBOUND since {@code unboundAt}. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Device(String deviceId, String status, Instant boundAt, Instant unboundAt) {}

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}

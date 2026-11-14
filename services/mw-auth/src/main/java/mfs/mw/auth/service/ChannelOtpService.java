package mfs.mw.auth.service;

import java.util.Map;
import mfs.mw.auth.dto.DeviceOtpSent;
import mfs.mw.auth.dto.InternalOtpRequest;
import mfs.mw.auth.dto.InternalOtpVerification;
import mfs.mw.auth.entity.AuthWallet;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * A channel's OTP bound to its own binding instead of a device (ticket 72, spec 9.3): the hosted
 * checkout page's OTP, purpose {@code CHECKOUT}, bound to the checkout sessionId, asked by
 * mw-checkout only. The same {@link DeviceOtpService} rules as a device OTP (hash only, one current
 * OTP per wallet, so it replaces a pending BIND/LOGIN/RESET OTP; 3 checks reserved atomically; the
 * wallet's sends per window); the wallet must be known and ACTIVE without an ADMIN lock (a PIN lock
 * is the assertion's to answer).
 */
@Service
public class ChannelOtpService {

  /** The one client that may ask a checkout OTP. */
  public static final String CHECKOUT_CALLER = "mw-checkout";

  private final WalletAccess wallets;
  private final DeviceOtpService otps;

  public ChannelOtpService(WalletAccess wallets, DeviceOtpService otps) {
    this.wallets = wallets;
    this.otps = otps;
  }

  public DeviceOtpSent send(String caller, InternalOtpRequest req) {
    requireCaller(caller);
    String msisdn = WalletAccess.msisdn(req.msisdn());
    AuthWallet w = known(msisdn);
    wallets.checkActive(w);
    return otps.send(w.walletId(), msisdn, req.bindingId(), req.locale(), DeviceOtpService.CHECKOUT,
        Map.of("amount", req.amount(), "merchant", req.merchant()));
  }

  /** Checks the OTP of the binding and spends it; returns only on a match. */
  public void verify(String caller, InternalOtpVerification req) {
    requireCaller(caller);
    String msisdn = WalletAccess.msisdn(req.msisdn());
    AuthWallet w = known(msisdn);
    DeviceOtpService.Checked checked = otps.check(w.walletId(), req.bindingId(), req.otp(), DeviceOtpService.CHECKOUT);
    otps.consume(w.walletId(), req.bindingId(), checked);
  }

  private static void requireCaller(String caller) {
    if (!CHECKOUT_CALLER.equals(caller)) {
      throw new MwException(ErrorCodes.AUTH_FORBIDDEN, "Not allowed").withStatus(HttpStatus.FORBIDDEN);
    }
  }

  private AuthWallet known(String msisdn) {
    return wallets.byMsisdn(msisdn).orElseThrow(() ->
        new MwException(ErrorCodes.AUTH_FORBIDDEN, "Wallet not known").withStatus(HttpStatus.FORBIDDEN));
  }
}

package mfs.mw.checkout.service;

import java.time.Clock;
import mfs.mw.checkout.config.CheckoutProperties;
import mfs.mw.checkout.dto.SessionStatus;
import mfs.mw.checkout.entity.CheckoutSession;
import mfs.mw.checkout.exception.ErrorCodes;
import mfs.mw.checkout.exception.MwException;
import mfs.mw.checkout.security.ResultSigner;
import org.springframework.stereotype.Component;

/**
 * The signed merchant URL for a terminal session (spec 9.3 step 3): PAID -> successUrl, CANCELLED
 * -> cancelUrl, FAILED and EXPIRED -> failUrl, each with the result and its HMAC-SHA256 signature
 * under the merchant's secret ({@link ResultSigner}).
 */
@Component
public class ResultLinks {

  private final CheckoutProperties props;
  private final Clock clock;

  public ResultLinks(CheckoutProperties props, Clock clock) {
    this.props = props;
    this.clock = clock;
  }

  /** The signed URL, or null while the session is not terminal. */
  public String redirectUrl(CheckoutSession s) {
    SessionStatus status = s.statusAt(clock.instant());
    if (!status.terminal()) {
      return null;
    }
    CheckoutProperties.Merchant m = props.merchant(s.merchantId());
    if (m == null || m.secret() == null || m.secret().isBlank()) {
      throw new MwException(ErrorCodes.SYS_INTERNAL, "Merchant signing secret is not configured");
    }
    ResultSigner.Result r =
        new ResultSigner.Result(
            s.sessionId().toString(),
            s.orderId(),
            status.name(),
            s.txnId() == null ? null : s.txnId().toString(),
            s.amountMinor(),
            s.currency(),
            status == SessionStatus.FAILED ? s.errorCode() : null,
            clock.instant().getEpochSecond());
    return ResultSigner.redirectUrl(s.returnUrlFor(status), m.secret(), r).toString();
  }
}

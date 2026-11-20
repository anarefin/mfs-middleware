package mfs.mw.orchestrator.service.saga;

import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * A merchant refund (ticket 116b, FRS CH-WEB-03, FL-06): a {@code REVERSAL} from a refund channel
 * ({@code mw.orchestrator.refund-channels}, default WEB_CHECKOUT) is its own transaction record. Its
 * one step is the core reversal of its amount under its own txnId ({@code RefundStarted} -> REVERSING
 * -> COMPLETED, or REJECTED on a core refusal). A refund is never itself reversed or inquired.
 * A SYSTEM reversal does not run this flow: it moves the original (ticket 19).
 */
@Component
public class RefundFlow implements Flow {

  public static final String TXN_TYPE = "REVERSAL";

  @Override
  public String txnType() {
    return TXN_TYPE;
  }

  @Override
  public boolean needsHold() {
    return false;
  }

  @Override
  public boolean allows(Step step) {
    return step == Step.REVERSE;
  }

  @Override
  public Set<Step> steps() {
    return EnumSet.of(Step.REVERSE);
  }
}

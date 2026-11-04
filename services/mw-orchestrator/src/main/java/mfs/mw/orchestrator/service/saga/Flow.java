package mfs.mw.orchestrator.service.saga;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * One flow definition per {@code txnType} (spec 7.1), selected by {@link FlowRegistry}, built from
 * the shared {@link Step}s. A flow without an external call posts in one step (P2P); a flow with
 * one holds after validation, calls its partner, then commits or releases the hold (TOPUP).
 */
public interface Flow {

  /** The {@code txnType} this flow runs. */
  String txnType();

  /** True when ValidateTransaction is followed by HoldFunds (flows with an external call). */
  boolean needsHold();

  /** The partner leg, for flows that hold; empty for flows that post directly. */
  default Optional<PartnerCall> partnerCall() {
    return Optional.empty();
  }

  /**
   * The steps this flow uses. The default follows {@link #needsHold()}: validate + post, or
   * validate + hold + callPartner (when there is a partner leg) + commit + release. Reversal
   * (ticket 19) and the core status query of a hold are not flow-specific and always allowed.
   */
  default Set<Step> steps() {
    if (!needsHold()) {
      return EnumSet.of(Step.VALIDATE, Step.POST);
    }
    Set<Step> s = EnumSet.of(Step.VALIDATE, Step.HOLD, Step.COMMIT, Step.RELEASE);
    if (partnerCall().isPresent()) {
      s.add(Step.CALL_PARTNER);
    }
    return s;
  }
}

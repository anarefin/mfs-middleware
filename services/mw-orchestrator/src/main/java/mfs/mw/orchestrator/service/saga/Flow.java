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
   * True for inbound money from a partner (ADD_MONEY_BANK, ticket 23): nothing is held, the
   * partner is asked right after validation, and the commit step is CreditWallet (the money the
   * partner collected goes into the credit wallet) instead of CommitHold.
   */
  default boolean creditsWallet() {
    return false;
  }

  /**
   * Whether this flow can perform {@code step}: its own steps, plus REVERSE and INQUIRE for every
   * flow (any COMPLETED transaction can be compensated once, spec 7.2; any flow can reach UNKNOWN,
   * 7.4) and QUERY for every flow that holds (HOLDING timeout).
   */
  default boolean allows(Step step) {
    Set<Step> own = steps();
    return own.contains(step)
        || step == Step.REVERSE
        || step == Step.INQUIRE
        || (step == Step.QUERY && own.contains(Step.HOLD));
  }

  /**
   * The steps this flow uses. The default follows {@link #needsHold()}: validate + post, or
   * validate + hold + callPartner (when there is a partner leg) + commit + release; inbound money
   * ({@link #creditsWallet()}) is validate + callPartner + commit. Reversal (ticket 19) and the
   * core status query of a hold are not flow-specific and always allowed.
   */
  default Set<Step> steps() {
    if (creditsWallet()) {
      return EnumSet.of(Step.VALIDATE, Step.CALL_PARTNER, Step.COMMIT);
    }
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

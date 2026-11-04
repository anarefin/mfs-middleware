package mfs.mw.orchestrator.service.saga;

/**
 * One flow definition per {@code txnType} (spec 7.1), selected by {@link FlowRegistry}. Today a
 * flow only says whether funds are held between validation and the partner call; later flows add
 * their steps here. P2P posts in one step, so it does not hold.
 */
public interface Flow {

  /** The {@code txnType} this flow runs. */
  String txnType();

  /** True when ValidateTransaction is followed by HoldFunds (flows with an external call). */
  boolean needsHold();
}

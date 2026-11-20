package mfs.mw.orchestrator.service;

/**
 * The core's event topics (spec 6.3, ADR D31): the core publishes its events on {@code
 * core.wallet.event.v1} and {@code core.payment.event.v1} (holds). Calls into the core are REST since
 * ticket 95a ({@code client/CoreWalletClient}); the orchestrator sends nothing on a core command topic.
 */
public final class CoreTopics {

  public static final String WALLET_EVENTS = "core.wallet.event.v1";
  public static final String PAYMENT_EVENTS = "core.payment.event.v1";

  private CoreTopics() {}
}

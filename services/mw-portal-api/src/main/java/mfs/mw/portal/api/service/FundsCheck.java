package mfs.mw.portal.api.service;

import java.util.OptionalLong;
import mfs.mw.portal.api.client.CoreBalanceClient;
import mfs.mw.portal.api.config.CoreClientProperties;
import mfs.mw.portal.api.exception.CoreUnavailableException;
import org.springframework.stereotype.Service;

/**
 * The org wallet balance check of a bulk batch (ticket 129c, FRS CH-POR-04): the wallet's available
 * balance read from the core against what the batch still has to pay. Off ({@code
 * mw.portal-api.core.balance-check}) it finds nothing short.
 */
@Service
public class FundsCheck {

  /** The core reason a short batch carries (spec 5.3 {@code MW-CORE-*}). */
  public static final String INSUFFICIENT_BALANCE = "MW-CORE-INSUFFICIENT_BALANCE";

  private final CoreBalanceClient core;
  private final boolean enabled;

  public FundsCheck(CoreBalanceClient core, CoreClientProperties props) {
    this.core = core;
    this.enabled = props.balanceCheck();
  }

  /**
   * How much {@code wallet} lacks to pay {@code neededMinor}; empty when it holds enough.
   *
   * @throws CoreUnavailableException the core gave no balance
   */
  public OptionalLong shortfall(String wallet, long neededMinor) {
    if (!enabled) {
      return OptionalLong.empty();
    }
    long available = core.available(wallet);
    return available >= neededMinor ? OptionalLong.empty() : OptionalLong.of(neededMinor - available);
  }
}

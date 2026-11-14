package mfs.mw.core.mock.mapper;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.core.mock.dto.Amount;
import mfs.mw.core.mock.dto.CoreCommand;
import mfs.mw.core.mock.dto.CoreEvent;
import mfs.mw.core.mock.entity.WalletState;
import mfs.mw.core.mock.util.UuidV7;
import org.springframework.stereotype.Component;

/** Builds the {@code core.event.v1} envelope for a command (spec 6.3). */
@Component
public class CoreEventMapper {

  public static final String WALLET_STATE_CHANGED = "WalletStateChanged";

  private final Clock clock;

  public CoreEventMapper(Clock clock) {
    this.clock = clock;
  }

  /** TransactionValidated: fee, charges and limits left. */
  public CoreEvent validated(CoreCommand cmd, String coreRef, long feeMinor, Map<String, Amount> limitsLeft) {
    Map<String, Object> payload = new java.util.LinkedHashMap<>();
    payload.put("fee", Amount.bdt(feeMinor));
    payload.put("charges", List.of(Map.of("code", "FLAT_FEE", "amount", Amount.bdt(feeMinor))));
    payload.put("limitsLeft", limitsLeft);
    return event(cmd, "TransactionValidated", coreRef, CoreEvent.Outcome.SUCCESS, null, null, null, payload);
  }

  /** TransferPosted with the balances of both wallets. */
  public CoreEvent posted(CoreCommand cmd, String coreRef, List<CoreEvent.BalanceAfter> balances) {
    return event(cmd, "TransferPosted", coreRef, CoreEvent.Outcome.SUCCESS, null, null, balances, Map.of());
  }

  /** The failure event of {@code cmd} (TransactionRejected or TransferRejected) with a core reason. */
  public CoreEvent rejected(CoreCommand cmd, String failureType, String coreRef, String reasonCode, String message) {
    return event(cmd, failureType, coreRef, CoreEvent.Outcome.FAILURE, reasonCode, message, null, Map.of());
  }

  /** CustomerFactorsRejected (ticket 70): carries the reset id the contract requires. */
  public CoreEvent factorsRejected(CoreCommand cmd, String coreRef, String reasonCode, String message, String resetId) {
    return event(cmd, "CustomerFactorsRejected", coreRef, CoreEvent.Outcome.FAILURE, reasonCode, message, null,
        Map.of("resetId", resetId));
  }

  /** A success event of any command type, with the given payload and optional balances. */
  public CoreEvent succeeded(
      CoreCommand cmd, String type, String coreRef, List<CoreEvent.BalanceAfter> balances, Map<String, Object> payload) {
    return event(cmd, type, coreRef, CoreEvent.Outcome.SUCCESS, null, null, balances, payload);
  }

  /**
   * WalletStateChanged (ticket 67, spec 18.7, D5): the wallet's state, answering no command. The
   * envelope's command fields get fresh values: a UUIDv7 txnId (mw-orchestrator finds no
   * transaction for it and drops the event), commandId {@code wallet-state:<txnId>}, coreRef
   * {@code WALLET-V<version>}; outcome SUCCESS.
   *
   * @param change CREATED, STATUS_CHANGED or SNAPSHOT
   */
  public CoreEvent walletStateChanged(WalletState w, String change) {
    String txnId = UuidV7.at(clock.instant()).toString();
    Map<String, Object> payload = new java.util.LinkedHashMap<>();
    payload.put("walletId", w.id());
    payload.put("msisdn", w.msisdn());
    payload.put("walletType", w.type());
    payload.put("status", w.status());
    // the core holds no PIN since ticket 72: mw-auth decides "has a PIN" from its auth.credential
    payload.put("hasPin", false);
    payload.put("version", w.version());
    payload.put("change", change);
    return new CoreEvent(
        1,
        WALLET_STATE_CHANGED,
        UUID.randomUUID().toString(),
        "wallet-state:" + txnId,
        txnId,
        "WALLET-V" + w.version(),
        clock.instant(),
        CoreEvent.Outcome.SUCCESS,
        null,
        null,
        null,
        payload);
  }

  /** The failure event type of a command (spec 6.3), or {@code null} for QueryTransaction. */
  public static String failureType(String commandType) {
    return switch (commandType) {
      case "ValidateTransaction" -> "TransactionRejected";
      case "PostTransfer" -> "TransferRejected";
      case "HoldFunds" -> "HoldRejected";
      case "CommitHold" -> "CommitFailed";
      case "ReleaseHold" -> "ReleaseFailed";
      case "CreditWallet" -> "CreditRejected";
      case "ReverseTransaction" -> "ReversalRejected";
      case "UnlockWallet" -> "UnlockRejected";
      case "VerifyCustomerFactors" -> "CustomerFactorsRejected";
      default -> null;
    };
  }

  /** A failure event that also carries a payload (the holdId of a hold command). */
  public CoreEvent rejected(
      CoreCommand cmd, String failureType, String coreRef, String reasonCode, String message, Map<String, Object> payload) {
    return event(cmd, failureType, coreRef, CoreEvent.Outcome.FAILURE, reasonCode, message, null, payload);
  }

  private CoreEvent event(
      CoreCommand cmd,
      String type,
      String coreRef,
      CoreEvent.Outcome outcome,
      String reasonCode,
      String reasonMessage,
      List<CoreEvent.BalanceAfter> balances,
      Map<String, Object> payload) {
    return new CoreEvent(
        1,
        type,
        UUID.randomUUID().toString(),
        cmd.commandId(),
        cmd.txnId(),
        coreRef,
        clock.instant(),
        outcome,
        reasonCode,
        reasonMessage,
        balances,
        payload);
  }
}

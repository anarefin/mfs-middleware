package mfs.mw.core.mock.mapper;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.core.mock.dto.Amount;
import mfs.mw.core.mock.dto.CoreCommand;
import mfs.mw.core.mock.dto.CoreEvent;
import org.springframework.stereotype.Component;

/** Builds the {@code core.event.v1} envelope for a command (spec 6.3). */
@Component
public class CoreEventMapper {

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

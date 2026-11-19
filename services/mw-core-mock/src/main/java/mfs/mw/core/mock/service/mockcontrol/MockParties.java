package mfs.mw.core.mock.service.mockcontrol;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import mfs.mw.core.mock.dto.MockParty;
import mfs.mw.core.mock.exception.ErrorCodes;
import mfs.mw.core.mock.exception.MwException;
import org.springframework.http.HttpStatus;

/**
 * The mock party of mw-core-mock (ADR D33, ticket 103): {@code core}, its operations (the WAL operations
 * of ticket 93 plus the Kafka-only commands) and scope keys. Copied shape of mw-simulators' list.
 */
public final class MockParties {

  /** Every operation of the core, by command type (Kafka and the REST money calls). */
  public static final Map<String, String> OPERATION_OF_COMMAND = Map.ofEntries(
      Map.entry("ValidateTransaction", "validate"),
      Map.entry("PostTransfer", "transfer"),
      Map.entry("HoldFunds", "hold"),
      Map.entry("CommitHold", "commit"),
      Map.entry("ReleaseHold", "release"),
      Map.entry("CreditWallet", "credit"),
      Map.entry("ReverseTransaction", "reversal"),
      Map.entry("QueryTransaction", "query"),
      Map.entry("BalanceInquiry", "balance"),
      Map.entry("UnlockWallet", "unlock"),
      Map.entry("VerifyCustomerFactors", "factors"));

  public static final MockParty CORE_PARTY = new MockParty(
      MockControl.CORE,
      "core wallet (ledger, holds, charges, limits; /core/v1 and the core.*.command.v1 commands)",
      List.of("validate", "hold", "commit", "release", "transfer", "credit", "reversal", "query", "balance",
          "charges", "limits", "accounts", "statements", "unlock", "factors"),
      List.of("debit", "credit", "wallet", "txnId", "holdId", "originalTxnId"));

  public static final List<MockParty> ALL = List.of(CORE_PARTY);

  private MockParties() {}

  public static Optional<MockParty> find(String name) {
    return ALL.stream().filter(p -> p.name().equals(name)).findFirst();
  }

  /** The party, or 404 MW-VAL-003. */
  public static MockParty require(String name) {
    return find(name).orElseThrow(
        () -> new MwException(ErrorCodes.VAL_NOT_FOUND, "No mock party named " + name).withStatus(HttpStatus.NOT_FOUND));
  }

  /** The operation of a command type, or null for a type the core does not handle. */
  public static String operationOf(String commandType) {
    return OPERATION_OF_COMMAND.get(commandType);
  }
}

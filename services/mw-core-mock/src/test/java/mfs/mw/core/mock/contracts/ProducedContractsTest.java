package mfs.mw.core.mock.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import mfs.mw.core.mock.dto.Amount;
import mfs.mw.core.mock.dto.CoreCommand;
import mfs.mw.core.mock.dto.CoreEvent;
import mfs.mw.core.mock.mapper.CoreEventMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every {@code produces.events} entry of {@code contracts.yml} (ticket 53): the event is built by
 * the real {@link CoreEventMapper}, with the payloads the service operations put in it
 * ({@code CoreCommandService}, {@code HoldOperations}, {@code PostingOperations},
 * {@code StatusOperations}), serialised the way {@code CoreCommandService} and {@code OutboxWriter}
 * do (to the {@code command_log} JSON, read back, written to the outbox), and validated against
 * {@code core.event.v1}. The JsonMapper is Spring Boot's default one: the module sets no
 * {@code spring.jackson.*} property and customises no mapper. No Spring context, no Docker.
 */
class ProducedContractsTest {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String EVENT = "events/mw-core-mock/core.event.v1.schema.json";
  static final String TXN = "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8c";
  static final String CORE_REF = "CORE-20261103-000001";
  static final String HOLD_ID = "HOLD-" + CORE_REF;

  static final CoreEventMapper EVENTS =
      new CoreEventMapper(Clock.fixed(Instant.parse("2026-11-03T08:15:31Z"), ZoneOffset.UTC));

  static CoreCommand command(String type, String step) {
    Instant issued = Instant.parse("2026-11-03T08:15:30Z");
    return new CoreCommand(
        1, type, TXN + ":" + step, TXN, "corr-0001", issued, issued.plusSeconds(5), JSON.createObjectNode());
  }

  static List<CoreEvent.BalanceAfter> balances() {
    return List.of(
        new CoreEvent.BalanceAfter("01711000001", Amount.bdt(1_200_000)),
        new CoreEvent.BalanceAfter("01711000002", Amount.bdt(800_000)));
  }

  static Map<String, Object> status(String txnStatus, String holdId) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("txnStatus", txnStatus);
    if (holdId != null) {
      payload.put("holdId", holdId);
    }
    return payload;
  }

  /** Manifest entry -> the event the module builds for it. */
  static final Map<String, Supplier<CoreEvent>> FIXTURES =
      Map.ofEntries(
          Map.entry(
              EVENT + "#TransactionValidated",
              () -> {
                Map<String, Amount> left = new LinkedHashMap<>();
                left.put("PER_TXN", Amount.bdt(2_500_000));
                left.put("DAILY", Amount.bdt(5_000_000));
                return EVENTS.validated(command("ValidateTransaction", "validate"), CORE_REF, 500, left);
              }),
          Map.entry(
              EVENT + "#TransactionRejected",
              () -> EVENTS.rejected(
                  command("ValidateTransaction", "validate"), "TransactionRejected", CORE_REF,
                  "INSUFFICIENT_BALANCE", "Insufficient balance")),
          Map.entry(
              EVENT + "#FundsHeld",
              () -> EVENTS.succeeded(command("HoldFunds", "hold"), "FundsHeld", CORE_REF, null, Map.of("holdId", HOLD_ID))),
          Map.entry(
              EVENT + "#HoldRejected",
              () -> EVENTS.rejected(command("HoldFunds", "hold"), "HoldRejected", CORE_REF, "INVALID_PIN", "Invalid PIN")),
          Map.entry(
              EVENT + "#HoldCommitted",
              () -> EVENTS.succeeded(
                  command("CommitHold", "commit"), "HoldCommitted", CORE_REF, balances().subList(0, 1),
                  Map.of("holdId", HOLD_ID))),
          Map.entry(
              EVENT + "#CommitFailed",
              () -> EVENTS.rejected(
                  command("CommitHold", "commit"), CoreEventMapper.failureType("CommitHold"), CORE_REF,
                  "HOLD_RELEASED", "Hold already released", Map.of("holdId", HOLD_ID))),
          Map.entry(
              EVENT + "#HoldReleased",
              () -> EVENTS.succeeded(
                  command("ReleaseHold", "release"), "HoldReleased", CORE_REF, balances().subList(0, 1),
                  Map.of("holdId", HOLD_ID))),
          Map.entry(
              EVENT + "#ReleaseFailed",
              () -> EVENTS.rejected(
                  command("ReleaseHold", "release"), CoreEventMapper.failureType("ReleaseHold"), CORE_REF,
                  "HOLD_ALREADY_COMMITTED", "Hold already committed", Map.of("holdId", HOLD_ID))),
          Map.entry(EVENT + "#TransferPosted", () -> EVENTS.posted(command("PostTransfer", "post"), CORE_REF, balances())),
          Map.entry(
              EVENT + "#TransferRejected",
              () -> EVENTS.rejected(
                  command("PostTransfer", "post"), "TransferRejected", CORE_REF, "LIMIT_EXCEEDED_DAILY",
                  "Daily limit exceeded")),
          Map.entry(
              EVENT + "#WalletCredited",
              () -> EVENTS.succeeded(
                  command("CreditWallet", "credit"), "WalletCredited", CORE_REF, balances().subList(0, 1), Map.of())),
          Map.entry(
              EVENT + "#CreditRejected",
              () -> EVENTS.rejected(
                  command("CreditWallet", "credit"), "CreditRejected", CORE_REF, "CREDIT_WALLET_NOT_FOUND",
                  "Credit wallet not found")),
          Map.entry(
              EVENT + "#TransactionReversed",
              () -> EVENTS.succeeded(
                  command("ReverseTransaction", "reverse"), "TransactionReversed", CORE_REF, balances(), Map.of())),
          Map.entry(
              EVENT + "#ReversalRejected",
              () -> EVENTS.rejected(
                  command("ReverseTransaction", "reverse"), "ReversalRejected", CORE_REF, "ALREADY_REVERSED",
                  "Already reversed")),
          Map.entry(
              EVENT + "#TransactionStatus",
              () -> EVENTS.succeeded(
                  command("QueryTransaction", "query"), "TransactionStatus", CORE_REF, null,
                  status("HELD", HOLD_ID))),
          Map.entry(
              EVENT + "#WalletUnlocked",
              () -> EVENTS.succeeded(
                  command("UnlockWallet", "unlock"), "WalletUnlocked", CORE_REF, null, Map.of("walletId", "01711000001"))),
          Map.entry(
              EVENT + "#UnlockRejected",
              () -> EVENTS.rejected(
                  command("UnlockWallet", "unlock"), CoreEventMapper.failureType("UnlockWallet"), CORE_REF,
                  "WALLET_NOT_LOCKED", "Wallet is not locked")));

  /** As CoreCommandService: stored in command_log as JSON, read back, appended to the outbox. */
  static JsonNode asPublished(CoreEvent decided) {
    String stored = JSON.writeValueAsString(decided);
    CoreEvent event = JSON.readValue(stored, CoreEvent.class);
    return JSON.readTree(JSON.writeValueAsString(event));
  }

  @TestFactory
  Stream<DynamicTest> everyProducedEventMatchesItsSchema() {
    return ContractManifest.producedEvents().stream()
        .map(
            entry ->
                DynamicTest.dynamicTest(
                    entry,
                    () -> {
                      assertThat(FIXTURES).as("fixture for manifest entry %s", entry).containsKey(entry);
                      JsonNode event = asPublished(FIXTURES.get(entry).get());
                      assertThat(ContractSchemas.schema(ContractManifest.schemaPath(entry)).validate(event))
                          .as("%s against its schema", entry)
                          .isEmpty();
                      assertThat(event.path("eventType").asString()).isEqualTo(ContractManifest.messageType(entry));
                    }));
  }

  /** A status answer without a hold (NOT_FOUND) leaves holdId out, as the schema needs. */
  @Test
  void statusWithoutHoldOmitsHoldId() {
    JsonNode event =
        asPublished(
            EVENTS.succeeded(command("QueryTransaction", "query"), "TransactionStatus", CORE_REF, null, status("NOT_FOUND", null)));
    assertThat(ContractSchemas.schema(EVENT).validate(event)).isEmpty();
    assertThat(event.path("payload").has("holdId")).isFalse();
  }

  @Test
  void fixturesAndManifestListTheSameEntries() {
    assertThat(FIXTURES.keySet()).containsExactlyInAnyOrderElementsOf(ContractManifest.producedEvents());
  }
}

package mfs.mw.admin.api.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import mfs.mw.admin.api.dto.AdminTxn;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.entity.ChangeRequest;
import mfs.mw.admin.api.mapper.ChangeRequestMapper;
import mfs.mw.admin.api.mapper.RevealAuditMapper;
import mfs.mw.admin.api.util.UuidV7;
import mfs.mw.admin.api.security.AdminRole;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every {@code produces.events} entry of {@code contracts.yml} (ticket 53): the message built by the
 * real mapper from a fixture, serialised as {@code messaging/OutboxWriter} does (the Boot-built
 * {@code JsonMapper}), validates against its schema. An entry without a fixture fails.
 */
class ProducedContractsTest {

  static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
  static final Instant AT = Instant.parse("2026-11-05T10:00:00Z");
  static final UUID TXN = UUID.fromString("019a5b2e-1c00-7abc-8def-000000000002");

  /** A PENDING request as the maker leaves it (ticket 37). */
  static ChangeRequest request(String action, String before, String after) {
    return new ChangeRequest(
        UUID.fromString("0d6c3f0e-1111-4b5e-9a1e-000000000001"), ChangeRequest.TRANSACTION, TXN.toString(), action,
        Map.of(), ChangeRequestMapper.diff(before, after), "ops-maker01", "sub-maker", ChangeRequest.Status.PENDING,
        null, null, "customer 01712345678 complained", null, AT, null, AT.plusSeconds(72 * 3600));
  }

  static final Map<String, Supplier<List<Object>>> FIXTURES =
      Map.of(
          "events/mw-audit-dw/mw.audit.event.v1.schema.json",
          () ->
              List.of(
                  RevealAuditMapper.event(
                      new Operator("sub-1", "ops-compliance01", Set.of(AdminRole.COMPLIANCE_OFFICER)),
                      AdminRole.COMPLIANCE_OFFICER, TXN, "corr-1", AT),
                  RevealAuditMapper.event(
                      new Operator("sub-2", "ops-auditor01", Set.of(AdminRole.AUDITOR)), AdminRole.AUDITOR, TXN, null, AT),
                  ChangeRequestMapper.created(request("REVERSE", "COMPLETED", "REVERSING"), "corr-1"),
                  ChangeRequestMapper.approved(request("FORCE_COMMIT", "MANUAL_REVIEW", "COMMITTING"), "ops-checker01",
                      "mw.txn.action.v1", "corr-1", AT),
                  ChangeRequestMapper.rejected(request("REVERSE", "COMPLETED", "REVERSING"), "ops-checker01", "no", null, AT),
                  ChangeRequestMapper.expired(request("INQUIRE", "UNKNOWN", "UNKNOWN"), "corr-1", AT),
                  // a wallet unlock (ticket 52): wallet/<masked MSISDN>, txnId the unlockTxnId
                  ChangeRequestMapper.created(unlockRequest(), "corr-1"),
                  ChangeRequestMapper.approved(unlockRequest(), "ops-checker01", "core.wallet.command.v1", "corr-1", AT),
                  ChangeRequestMapper.rejected(unlockRequest(), "ops-checker01", "no", null, AT),
                  ChangeRequestMapper.expired(unlockRequest(), "corr-1", AT)),
          "events/mw-admin-api/mw.txn.action.v1.schema.json",
          () ->
              List.of(
                  ChangeRequestMapper.action(request("FORCE_COMMIT", "MANUAL_REVIEW", "COMMITTING"), UUID.randomUUID(),
                      "ops-checker01", "corr-1", AT),
                  ChangeRequestMapper.action(request("INQUIRE", "UNKNOWN", "UNKNOWN"), UUID.randomUUID(),
                      "ops-checker01", "corr-1", AT)),
          "events/mw-orchestrator/mw.txn.request.v1.schema.json",
          () ->
              List.of(
                  ChangeRequestMapper.reversal(request("REVERSE", "COMPLETED", "REVERSING"), UuidV7.at(AT),
                      new AdminTxn.Amount(5000, "BDT"), "ops-checker01", "corr-1", AT)),
          "events/mw-orchestrator/core.command.v1.schema.json#UnlockWallet",
          () ->
              List.of(
                  ChangeRequestMapper.unlock(unlockRequest(), "ops-checker01", "corr-1", AT, Duration.ofHours(1)),
                  ChangeRequestMapper.unlock(unlockRequest(), "ops-checker01", "admin-" + UUID.randomUUID(), AT,
                      Duration.ofMinutes(5))));

  /** A PENDING wallet unlock as the maker leaves it (ticket 52). */
  static ChangeRequest unlockRequest() {
    return new ChangeRequest(
        UUID.fromString("0d6c3f0e-2222-4b5e-9a1e-000000000003"), ChangeRequest.WALLET, "01711000123", "UNLOCK",
        Map.of(ChangeRequestMapper.UNLOCK_TXN_ID, UuidV7.at(AT).toString()), ChangeRequestMapper.diff("LOCKED", "ACTIVE"),
        "ops-maker01", "sub-maker", ChangeRequest.Status.PENDING, null, null, "verified at branch", null, AT, null,
        AT.plusSeconds(72 * 3600));
  }

  static final List<String> ENTRIES = ContractManifest.load().producedEvents();

  @TestFactory
  Stream<DynamicTest> everyProducedEventMatchesItsSchema() {
    return ENTRIES.stream()
        .map(
            entry ->
                DynamicTest.dynamicTest(
                    entry,
                    () -> {
                      assertThat(FIXTURES).as("fixture for manifest entry %s", entry).containsKey(entry);
                      List<Object> messages = FIXTURES.get(entry).get();
                      assertThat(messages).isNotEmpty();
                      for (Object m : messages) {
                        JsonNode node = JSON.readTree(JSON.writeValueAsString(m));
                        assertThat(ContractSchemas.schema(ContractManifest.schemaPath(entry)).validate(node))
                            .as("%s: %s", entry, node)
                            .isEmpty();
                      }
                    }));
  }

  @Test
  void everyFixtureIsInTheManifest() {
    assertThat(ENTRIES).isNotEmpty();
    assertThat(ENTRIES).containsExactlyInAnyOrderElementsOf(FIXTURES.keySet());
  }
}

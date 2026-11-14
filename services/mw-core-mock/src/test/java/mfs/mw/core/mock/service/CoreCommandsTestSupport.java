package mfs.mw.core.mock.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import mfs.mw.core.mock.contracts.ContractSchemas;
import mfs.mw.core.mock.dto.Amount;
import mfs.mw.core.mock.dto.CoreCommand;
import mfs.mw.core.mock.dto.CoreEvent;
import mfs.mw.core.mock.dto.Party;
import mfs.mw.core.mock.security.TestAssertions;
import mfs.mw.core.mock.util.TxnHash;
import tools.jackson.databind.json.JsonMapper;

/** Builds contract-shaped commands and checks every result event against {@code core.event.v1}. */
public final class CoreCommandsTestSupport {

  static final String EVENT_SCHEMA = "events/mw-core-mock/core.event.v1.schema.json";
  static final String COMMAND_SCHEMA = "events/mw-orchestrator/core.command.v1.schema.json";

  private final JsonMapper json;

  public CoreCommandsTestSupport(JsonMapper json) {
    this.json = json;
  }

  /** A random UUID with the version nibble set to 7: the shape the TxnId contract requires. */
  public static String txnId() {
    String u = UUID.randomUUID().toString();
    return u.substring(0, 14) + "7" + u.substring(15);
  }

  public CoreCommand command(String type, String txnId, String commandId, Map<String, Object> payload) {
    Instant now = Instant.now();
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("schemaVersion", 1);
    envelope.put("commandType", type);
    envelope.put("commandId", commandId);
    envelope.put("txnId", txnId);
    envelope.put("correlationId", "corr-" + txnId);
    envelope.put("issuedAt", now.toString());
    envelope.put("expiresAt", now.plusSeconds(300).toString());
    envelope.put("payload", payload);
    assertThat(ContractSchemas.schema(COMMAND_SCHEMA).validate(json.valueToTree(envelope)))
        .as("command %s against core.command.v1", type)
        .isEmpty();
    return json.convertValue(envelope, CoreCommand.class);
  }

  /** A transfer from {@code from} authorised by mw-auth's assertion for the payer (ticket 72: no PIN). */
  public Map<String, Object> transfer(String txnType, String from, String to, long minor) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("txnType", txnType);
    p.put("debitParty", new Party("WALLET", from, null, "CUSTOMER"));
    p.put("creditParty", new Party("WALLET", to, null, "CUSTOMER"));
    p.put("amount", Amount.bdt(minor));
    return authorised(p, from);
  }

  /**
   * Adds a fresh idempotency key and an assertion of {@code walletId} (the payer, or the payee of
   * ADD_MONEY_BANK) whose txnHash matches the payload, as mw-auth signs it ({@link TestAssertions}).
   */
  public static Map<String, Object> authorised(Map<String, Object> p, String walletId) {
    String key = UUID.randomUUID().toString();
    Party debit = (Party) p.get("debitParty");
    Party credit = (Party) p.get("creditParty");
    Amount amount = (Amount) p.get("amount");
    p.put("assertion", TestAssertions.mint(TestAssertions.claims(
        walletId, (String) p.get("txnType"), new TxnHash.Party(debit.type(), debit.id()),
        new TxnHash.Party(credit.type(), credit.id()), amount.valueMinor(), key).build()));
    p.put("idempotencyKey", key);
    return p;
  }

  public CoreCommand holdFunds(String txnId, String from, long minor) {
    return command("HoldFunds", txnId, txnId + ":hold", transfer("TOPUP", from, from, minor));
  }

  public CoreCommand commitHold(String txnId, String holdId) {
    return command("CommitHold", txnId, txnId + ":commit", Map.of("holdId", holdId));
  }

  public CoreCommand releaseHold(String txnId, String holdId) {
    return command("ReleaseHold", txnId, txnId + ":release", Map.of("holdId", holdId));
  }

  public CoreCommand creditWallet(String txnId, String to, long minor) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("txnType", "ADD_MONEY_BANK");
    p.put("creditParty", new Party("WALLET", to, null, "CUSTOMER"));
    p.put("amount", Amount.bdt(minor));
    return command("CreditWallet", txnId, txnId + ":credit", p);
  }

  public CoreCommand validate(String txnId, String from, String to, long minor) {
    return command("ValidateTransaction", txnId, txnId + ":validate", transfer("P2P", from, to, minor));
  }

  public CoreCommand post(String txnId, String from, String to, long minor) {
    return command("PostTransfer", txnId, txnId + ":post", transfer("P2P", from, to, minor));
  }

  public CoreCommand reverse(String txnId, String originalTxnId, long minor) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("originalTxnId", originalTxnId);
    p.put("amount", Amount.bdt(minor));
    p.put("reason", "test");
    return command("ReverseTransaction", txnId, txnId + ":reverse", p);
  }

  /** A status inquiry for everything known about {@code txnId}. */
  public CoreCommand query(String txnId) {
    return command("QueryTransaction", txnId, txnId + ":query:" + UUID.randomUUID(), Map.of());
  }

  /** A status inquiry narrowed to one earlier command. */
  public CoreCommand queryCommand(String txnId, String queryCommandId) {
    return command(
        "QueryTransaction",
        txnId,
        txnId + ":query:" + UUID.randomUUID(),
        Map.of("queryCommandId", queryCommandId));
  }

  /** Fails unless the event is valid against the owned contract and answers the command. */
  public CoreEvent valid(CoreEvent e, CoreCommand c) {
    assertThat(ContractSchemas.schema(EVENT_SCHEMA).validate(json.valueToTree(e)))
        .as("event %s against core.event.v1", e.eventType())
        .isEmpty();
    assertThat(e.commandId()).isEqualTo(c.commandId());
    assertThat(e.txnId()).isEqualTo(c.txnId());
    return e;
  }
}

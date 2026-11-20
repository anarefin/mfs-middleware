package mfs.mw.orchestrator;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import mfs.mw.orchestrator.client.CoreWalletClient;
import mfs.mw.orchestrator.dto.CoreCommand;
import mfs.mw.orchestrator.dto.CoreEvent;
import mfs.mw.orchestrator.dto.CoreReply;
import mfs.mw.orchestrator.service.CoreCallExecutor;
import mfs.mw.orchestrator.service.CoreReplyService;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The core as the saga's service-seam ITs see it (ticket 95a): every core call a step makes is
 * recorded, in line on the test thread once the step's transaction commits, and gets no answer (an
 * unknown outcome, as a silent core); the test answers it with {@link #answer(CoreEvent)}, written in
 * the core's result names (TransactionValidated, FundsHeld, ...), which become the REST answer of that
 * step and go through {@link CoreReplyService} exactly as a real answer does. Import it next to
 * {@link TestInfrastructure}; {@link #commands(String)} replaces reading the old command topic.
 */
@TestConfiguration(proxyBeanMethods = false)
public class CoreCalls {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The call behind each core result name. */
  private static final Map<String, String> CALL = Map.ofEntries(
      Map.entry("TransactionValidated", "ValidateTransaction"), Map.entry("TransactionRejected", "ValidateTransaction"),
      Map.entry("TransferPosted", "PostTransfer"), Map.entry("TransferRejected", "PostTransfer"),
      Map.entry("FundsHeld", "HoldFunds"), Map.entry("HoldRejected", "HoldFunds"),
      Map.entry("HoldCommitted", "CommitHold"), Map.entry("CommitFailed", "CommitHold"),
      Map.entry("HoldReleased", "ReleaseHold"), Map.entry("ReleaseFailed", "ReleaseHold"),
      Map.entry("WalletCredited", "CreditWallet"), Map.entry("CreditRejected", "CreditWallet"),
      Map.entry("TransactionReversed", "ReverseTransaction"), Map.entry("ReversalRejected", "ReverseTransaction"),
      Map.entry("TransactionStatus", "QueryTransaction"),
      Map.entry("BalanceReported", "BalanceInquiry"), Map.entry("BalanceInquiryRejected", "BalanceInquiry"));

  /** The core's refusal names: a refusal whatever the event's outcome field says. */
  private static final java.util.Set<String> REFUSALS = java.util.Set.of("TransactionRejected", "TransferRejected",
      "HoldRejected", "CommitFailed", "ReleaseFailed", "CreditRejected", "ReversalRejected", "BalanceInquiryRejected");

  private final List<CoreCommand> calls = new CopyOnWriteArrayList<>();
  private final ObjectProvider<CoreReplyService> replies;

  public CoreCalls(ObjectProvider<CoreReplyService> replies) {
    this.replies = replies;
  }

  @Bean
  @Primary
  CoreCallExecutor inlineCoreCalls() {
    return Runnable::run;
  }

  @Bean
  @Primary
  CoreWalletClient recordingCoreClient() {
    return Mockito.mock(CoreWalletClient.class, invocation -> {
      CoreCommand command = invocation.getArgument(0);
      calls.add(command);
      return CoreReply.ambiguous(command, 0);
    });
  }

  /** The core calls made under {@code txnId} (a reversal's under the reversal's), oldest first, as JSON. */
  public List<JsonNode> commands(String txnId) {
    return calls.stream().filter(c -> txnId.equals(c.txnId())).map(c -> JSON.<JsonNode>valueToTree(c)).toList();
  }

  /** Every recorded call. */
  public List<CoreCommand> all() {
    return List.copyOf(calls);
  }

  /** Every recorded call as JSON, whatever its txnId (tests that clear between cases). */
  public List<JsonNode> commands() {
    return calls.stream().map(c -> JSON.<JsonNode>valueToTree(c)).toList();
  }

  /** Forgets the recorded calls (the context, and so this recorder, is shared by a class's tests). */
  public void clear() {
    calls.clear();
  }

  /** Answers a call with the core's result {@code event} turned into the step's REST answer. */
  public void answer(CoreEvent event) {
    replies.getObject().handle(reply(event));
  }

  /** The REST answer of the step a core result names (unknown names: a call no flow makes). */
  public static CoreReply reply(CoreEvent e) {
    String call = CALL.getOrDefault(e.eventType(), e.eventType());
    if ("FAILURE".equals(e.outcome()) || REFUSALS.contains(e.eventType())) {
      String reason = e.reasonCode() == null ? "REJECTED" : e.reasonCode();
      String code = reason.startsWith("MW-") ? reason : "MW-CORE-" + reason;
      ObjectNode envelope = JSON.createObjectNode().put("code", code).put("message", "refused")
          .put("correlationId", "c").put("retryable", false);
      envelope.putArray("details");
      return new CoreReply(call, e.commandId(), e.txnId(), CoreReply.Kind.REFUSED,
          "MW-AUTH-046".equals(code) ? 401 : 422, code, envelope);
    }
    ObjectNode body = e.payload() instanceof ObjectNode p ? p.deepCopy() : JSON.createObjectNode();
    body.put("txnId", e.txnId());
    if (e.coreRef() != null) {
      body.put("coreRef", e.coreRef());
    }
    if ("TransactionStatus".equals(e.eventType()) && body.has("txnStatus")) {
      body.set("status", body.get("txnStatus"));
    }
    return new CoreReply(call, e.commandId(), e.txnId(), CoreReply.Kind.ANSWERED, 200, null, body);
  }
}

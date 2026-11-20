package mfs.mw.orchestrator.client;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import java.io.IOException;
import java.net.http.HttpClient;
import java.util.function.Supplier;
import mfs.mw.orchestrator.config.OrchestratorProperties;
import mfs.mw.orchestrator.dto.CoreCallBodies;
import mfs.mw.orchestrator.dto.CoreCommand;
import mfs.mw.orchestrator.dto.CoreReply;
import mfs.mw.orchestrator.dto.CreditPayload;
import mfs.mw.orchestrator.dto.HoldPayload;
import mfs.mw.orchestrator.dto.ReversePayload;
import mfs.mw.orchestrator.dto.TransferPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The core wallet over REST (ADR D31, ticket 95a; contract {@code
 * api/mw-core-mock/core-wallet.v1.openapi.yaml}, FRS WAL-03, 06..11, 13, EXT-18, PLT-030/033). One
 * call per saga step, made on a virtual thread of {@code service/CoreCallDispatcher}, never on a Kafka
 * listener thread:
 *
 * <ul>
 *   <li>ValidateTransaction {@code POST /core/v1/transactions/validate}, PostTransfer {@code POST
 *       /transfers}, HoldFunds {@code POST /holds}: the transfer body, mw-auth's assertion in {@code
 *       X-Txn-Assertion} (none for a bulk row);
 *   <li>CommitHold / ReleaseHold {@code POST /holds/{holdId}/commit|release}, CreditWallet {@code POST
 *       /credits}: an operator's n-th forced decision ({@code :manual-<n>} commandId) sends {@code round}
 *       n, so the core decides it afresh;
 *   <li>ReverseTransaction {@code POST /reversals} under the reversal's txnId, without amount (the
 *       whole original, fee included);
 *   <li>QueryTransaction (inquiry, HOLDING query, reversal query, credit check) {@code GET
 *       /transactions/{txnId}} (WAL-13);
 *   <li>BalanceInquiry {@code GET /balance/{walletId}?txnId=} with the assertion and {@code
 *       Idempotency-Key}.
 * </ul>
 *
 * Every money call is idempotent by txnId at the core (FRS 7.3), but this client never retries one:
 * the step's deadline resends it (same txnId) or asks WAL-13. Only the reads (WAL-13, the balance) are
 * retried ({@code resilience4j.retry.instances.core-read}). Connect and read timeouts ({@code
 * mw.orchestrator.core}), circuit breaker and bulkhead {@code core}. The service token (Keycloak realm
 * {@code services}, audience {@code mw-core-mock}) is renewed once when the core answers 401 {@code
 * MW-AUTH-001}. Nothing sent or received is logged.
 */
@Component
public class CoreWalletClient {

  static final String BREAKER = "core";
  static final String BULKHEAD = "core";
  static final String READ_RETRY = "core-read";
  static final String TOKEN_REFUSED = "MW-AUTH-001";
  static final String ASSERTION_HEADER = "X-Txn-Assertion";

  private static final Logger log = LoggerFactory.getLogger(CoreWalletClient.class);

  /** The answer of one HTTP exchange before classification. */
  private record Answer(int status, JsonNode body) {}

  /** A response that may not be final (5xx, 408, 429): counted by the breaker, retried for reads. */
  private static final class NoDefiniteAnswer extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient Answer answer;

    NoDefiniteAnswer(Answer answer) {
      super("core answered " + answer.status(), null, false, false);
      this.answer = answer;
    }
  }

  private final RestClient http;
  private final ServiceTokenClient tokens;
  private final CircuitBreaker breaker;
  private final Bulkhead bulkhead;
  private final Retry readRetry;
  private final JsonMapper json;

  public CoreWalletClient(
      OrchestratorProperties props,
      ServiceTokenClient tokens,
      CircuitBreakerRegistry breakers,
      BulkheadRegistry bulkheads,
      RetryRegistry retries,
      JsonMapper json) {
    OrchestratorProperties.Core core = props.core();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
        HttpClient.newBuilder().connectTimeout(core.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build());
    factory.setReadTimeout(core.readTimeout());
    this.http = RestClient.builder().baseUrl(core.baseUrl().toString()).requestFactory(factory).build();
    this.tokens = tokens;
    this.breaker = breakers.circuitBreaker(BREAKER);
    this.bulkhead = bulkheads.bulkhead(BULKHEAD);
    this.readRetry = retries.retry(READ_RETRY);
    this.json = json;
  }

  /** Makes the call of {@code command}'s step and classifies the answer; never throws. */
  public CoreReply call(CoreCommand command) {
    Supplier<Answer> exchange = switch (command.commandType()) {
      case "ValidateTransaction" -> () -> transfer(command, "/core/v1/transactions/validate");
      case "PostTransfer" -> () -> transfer(command, "/core/v1/transfers");
      case "HoldFunds" -> () -> transfer(command, "/core/v1/holds");
      case "CommitHold" -> () -> holdAction(command, "commit");
      case "ReleaseHold" -> () -> holdAction(command, "release");
      case "CreditWallet" -> () -> credit(command);
      case "ReverseTransaction" -> () -> reverse(command);
      case "QueryTransaction" ->
          () -> send(HttpMethod.GET, "/core/v1/transactions/{txnId}", vars(command.txnId()), command, null, null, null);
      case "BalanceInquiry" -> () -> balance(command);
      default -> throw new IllegalArgumentException("no core call for " + command.commandType());
    };
    boolean read = "QueryTransaction".equals(command.commandType()) || "BalanceInquiry".equals(command.commandType());
    Supplier<Answer> guarded = Bulkhead.decorateSupplier(bulkhead, CircuitBreaker.decorateSupplier(breaker, exchange));
    if (read) {
      guarded = Retry.decorateSupplier(readRetry, guarded);
    }
    try {
      return classify(command, guarded.get());
    } catch (NoDefiniteAnswer e) {
      return ambiguous(command, e.answer.status(), "status");
    } catch (RuntimeException e) {
      // timeout, connection, breaker open, bulkhead full, token endpoint down
      return ambiguous(command, 0, e.getClass().getSimpleName());
    }
  }

  private CoreReply classify(CoreCommand command, Answer a) {
    if (a.status() >= 200 && a.status() < 300) {
      return new CoreReply(command.commandType(), command.commandId(), command.txnId(), CoreReply.Kind.ANSWERED,
          a.status(), null, a.body());
    }
    String code = a.body() == null ? null : a.body().path("code").asString(null);
    // 403 (this client not allowed), 409 (the same txnId with another body), a refused service token:
    // the call did not decide the step; logged, and the deadline settles it
    if (a.status() == 403 || a.status() == 409 || TOKEN_REFUSED.equals(code)) {
      log.atError()
          .addKeyValue("event", "core.call.not_decided")
          .addKeyValue("txnId", command.txnId())
          .addKeyValue("commandType", command.commandType())
          .addKeyValue("status", a.status())
          .addKeyValue("errorCode", code)
          .log("core call refused for a reason that is not the core's decision");
      return CoreReply.ambiguous(command, a.status());
    }
    return new CoreReply(command.commandType(), command.commandId(), command.txnId(), CoreReply.Kind.REFUSED,
        a.status(), code, a.body());
  }

  private static CoreReply ambiguous(CoreCommand command, int status, String cause) {
    log.atWarn()
        .addKeyValue("event", "core.call.ambiguous")
        .addKeyValue("txnId", command.txnId())
        .addKeyValue("commandType", command.commandType())
        .addKeyValue("status", status)
        .addKeyValue("errorClass", cause)
        .log("core call gave no definite answer; the step's deadline settles it");
    return CoreReply.ambiguous(command, status);
  }

  private Answer transfer(CoreCommand command, String path) {
    TransferPayload p = (TransferPayload) command.payload();
    CoreCallBodies.Transfer body = new CoreCallBodies.Transfer(command.txnId(), p.txnType(), p.debitParty(), p.creditParty(),
        p.amount(), p.idempotencyKey());
    return send(HttpMethod.POST, path, vars(), command, body, p.assertion(), null);
  }

  private Answer holdAction(CoreCommand command, String action) {
    HoldPayload p = (HoldPayload) command.payload();
    Integer round = round(command.commandId());
    return send(HttpMethod.POST, "/core/v1/holds/{holdId}/" + action, vars(p.holdId()), command,
        round == null && p.reason() == null ? null : new CoreCallBodies.HoldAction(p.reason(), round), null, null);
  }

  private Answer credit(CoreCommand command) {
    CreditPayload p = (CreditPayload) command.payload();
    return send(HttpMethod.POST, "/core/v1/credits", vars(), command,
        new CoreCallBodies.Credit(command.txnId(), p.txnType(), p.creditParty(), p.amount(), round(command.commandId())),
        null, null);
  }

  private Answer reverse(CoreCommand command) {
    ReversePayload p = (ReversePayload) command.payload();
    return send(HttpMethod.POST, "/core/v1/reversals", vars(), command,
        new CoreCallBodies.Reversal(command.txnId(), p.originalTxnId(), p.reason()), null, null);
  }

  private Answer balance(CoreCommand command) {
    TransferPayload p = (TransferPayload) command.payload();
    return send(HttpMethod.GET, "/core/v1/balance/{walletId}?txnId={txnId}", vars(p.debitParty().id(), command.txnId()),
        command, null, p.assertion(), p.idempotencyKey());
  }

  /** The operator round of a {@code ...:manual-<n>} commandId, else null (the saga's own call). */
  static Integer round(String commandId) {
    int i = commandId == null ? -1 : commandId.lastIndexOf(":manual-");
    if (i < 0) {
      return null;
    }
    try {
      return Integer.valueOf(commandId.substring(i + ":manual-".length()));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** URI template variables: every id goes in through one, never concatenated (security review of 95a). */
  private static Object[] vars(Object... values) {
    return values;
  }

  /**
   * One exchange; a 401 MW-AUTH-001 renews the service token and repeats it once. {@code template} is a
   * fixed URI template whose variables ({@code uriVars}) RestClient encodes strictly: an id is one path
   * segment or one query value, whatever {@code /}, {@code ?}, {@code &} or {@code ..} it holds.
   */
  private Answer send(HttpMethod method, String template, Object[] uriVars, CoreCommand command, Object body,
      String assertion, String key) {
    Answer a = exchange(method, template, uriVars, command, body, assertion, key);
    if (a.status() == 401 && a.body() != null && TOKEN_REFUSED.equals(a.body().path("code").asString(null))) {
      tokens.invalidate();
      a = exchange(method, template, uriVars, command, body, assertion, key);
    }
    if (a.status() >= 500 || a.status() == 408 || a.status() == 429) {
      throw new NoDefiniteAnswer(a);
    }
    return a;
  }

  private Answer exchange(HttpMethod method, String template, Object[] uriVars, CoreCommand command, Object body,
      String assertion, String key) {
    RestClient.RequestBodySpec req = http.method(method).uri(template, uriVars)
        .accept(MediaType.APPLICATION_JSON)
        .header("Authorization", "Bearer " + tokens.token());
    if (command.correlationId() != null) {
      req = req.header("X-Correlation-Id", command.correlationId());
    }
    if (assertion != null) {
      req = req.header(ASSERTION_HEADER, assertion);
    }
    if (key != null) {
      req = req.header("Idempotency-Key", key);
    }
    if (body != null) {
      req = req.contentType(MediaType.APPLICATION_JSON).body(body);
    }
    return req.exchange((request, response) -> new Answer(response.getStatusCode().value(), read(response.getBody().readAllBytes())));
  }

  private JsonNode read(byte[] bytes) throws IOException {
    if (bytes.length == 0) {
      return null;
    }
    try {
      return json.readTree(bytes);
    } catch (JacksonException e) {
      return null;
    }
  }
}

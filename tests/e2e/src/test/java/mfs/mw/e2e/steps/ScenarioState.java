package mfs.mw.e2e.steps;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.e2e.support.AppApi;
import mfs.mw.e2e.support.CoreMock;
import mfs.mw.e2e.support.Http;
import tools.jackson.databind.node.ObjectNode;

/**
 * What one scenario remembers between steps (a new instance per scenario, shared by every step
 * class through Cucumber's PicoContainer constructor injection).
 */
public class ScenarioState {

  /** Signed-in app user (Keycloak username) of the scenario. */
  String user;

  /** Idempotency-Key of the last submitted request. */
  String idempotencyKey;

  /** Body of the last submitted request. */
  ObjectNode lastRequest;

  /** The last submit exactly as sent (body and signed headers), for replay steps (ticket 29). */
  AppApi.SignedSubmit lastSubmit;

  /** Every submit response in order; the last one is "the response". */
  final List<Http.Response> responses = new ArrayList<>();

  /** A core fault was injected; an {@code @After} hook resets the faults. */
  boolean faultsInjected;

  /** A partner simulator scenario was forced; an {@code @After} hook resets it. */
  boolean simulatorForced;

  /** Idempotency keys of the REVERSAL requests the scenario sent, in order. */
  final List<String> reversalKeys = new ArrayList<>();

  /** batchId of the last bulk batch the scenario uploaded (org portal, ticket 33). */
  String batchId;

  /** Balances noted before the action, by wallet id. */
  final Map<String, CoreMock.Balance> notedBalances = new LinkedHashMap<>();

  Http.Response response() {
    if (responses.isEmpty()) {
      throw new IllegalStateException("no request was sent in this scenario");
    }
    return responses.getLast();
  }

  /** txnId of the first response that carried one (the transaction the scenario is about). */
  String txnId() {
    return responses.stream()
        .map(r -> r.body().path("txnId").asString(null))
        .filter(id -> id != null)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("no response carried a txnId: " + responses));
  }
}

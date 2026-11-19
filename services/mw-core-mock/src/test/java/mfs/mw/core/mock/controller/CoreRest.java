package mfs.mw.core.mock.controller;

import java.util.UUID;
import mfs.mw.core.mock.security.InternalTokens;
import mfs.mw.core.mock.security.TestAssertions;
import mfs.mw.core.mock.service.CoreCommandsTestSupport;
import mfs.mw.core.mock.util.TxnHash;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The calls the mock-control tests make (ticket 103): /core/v1 as mw-orchestrator, /mock-control as a tester. */
final class CoreRest {

  private final MockMvcTester mvc;
  private final JsonMapper json;

  CoreRest(MockMvcTester mvc, JsonMapper json) {
    this.mvc = mvc;
    this.json = json;
  }

  /** A transfer-shaped body and the assertion mw-auth would sign for it. */
  record Signed(String txnId, String body, String assertion) {}

  static Signed signed(String txnType, String from, String toType, String to, long minor) {
    String txnId = CoreCommandsTestSupport.txnId();
    String key = UUID.randomUUID().toString();
    String assertion = TestAssertions.mint(TestAssertions.claims(from, txnType, new TxnHash.Party("WALLET", from),
        new TxnHash.Party(toType, to), minor, key).build());
    String body = """
        {"txnId":"%s","txnType":"%s","debitParty":{"type":"WALLET","id":"%s"},"creditParty":{"type":"%s","id":"%s"},
         "amount":{"valueMinor":%d,"currency":"BDT"},"idempotencyKey":"%s"}""".formatted(txnId, txnType, from, toType, to, minor, key);
    return new Signed(txnId, body, assertion);
  }

  MvcTestResult core(String uri, Signed s) {
    return mvc.post().uri(uri).header("Authorization", InternalTokens.bearer("mw-orchestrator"))
        .header("X-Txn-Assertion", s.assertion()).contentType(MediaType.APPLICATION_JSON).content(s.body()).exchange();
  }

  MvcTestResult corePost(String uri, String body) {
    return mvc.post().uri(uri).header("Authorization", InternalTokens.bearer("mw-orchestrator"))
        .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
  }

  MvcTestResult coreGet(String uri) {
    return mvc.get().uri(uri).header("Authorization", InternalTokens.bearer("mw-orchestrator")).exchange();
  }

  long available(String walletId) {
    return body(coreGet("/core/v1/balance/" + walletId)).path("available").path("valueMinor").asLong();
  }

  MvcTestResult control(String method, String uri, String body) {
    var req = switch (method) {
      case "POST" -> mvc.post().uri(uri);
      case "PUT" -> mvc.put().uri(uri);
      case "DELETE" -> mvc.delete().uri(uri);
      default -> mvc.get().uri(uri);
    };
    if (body != null) {
      req = req.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    return req.exchange();
  }

  JsonNode body(MvcTestResult r) {
    return json.readTree(r.getResponse().getContentAsByteArray());
  }
}

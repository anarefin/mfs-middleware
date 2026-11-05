package mfs.mw.portal.api.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.portal.api.dto.Amount;
import mfs.mw.portal.api.dto.BatchList;
import mfs.mw.portal.api.dto.BatchProgress;
import mfs.mw.portal.api.dto.BatchView;
import mfs.mw.portal.api.dto.ErrorDetail;
import mfs.mw.portal.api.dto.ErrorEnvelope;
import mfs.mw.portal.api.dto.OrgTxn;
import mfs.mw.portal.api.dto.Party;
import mfs.mw.portal.api.entity.Batch;
import mfs.mw.portal.api.entity.BatchStatus;
import mfs.mw.portal.api.mapper.BatchMapper;
import mfs.mw.portal.api.util.BulkIds;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The APIs of {@code contracts.yml} (ticket 53). Served ({@code api/mw-portal-api/portal-org.v1}):
 * the response DTOs, built by the real mapper and serialised as Spring MVC does (the Boot-built
 * JsonMapper), validate against the contract's schemas. Called ({@code api/mw-orchestrator/
 * txn-query.v1}): the client sends GETs with query parameters only (no request body to check); a
 * contract-shaped answer (validated against the contract first) reads into {@code dto/OrgTxn}.
 */
class ApiContractsTest {

  static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
  static final String PORTAL = "api/mw-portal-api/portal-org.v1.openapi.yaml";
  static final String QUERY = "api/mw-orchestrator/txn-query.v1.openapi.yaml";
  static final String BATCHES = "/portal/org/v1/bulk/batches";
  static final String TXNS = "/portal/org/v1/transactions";

  static final Instant AT = Instant.parse("2026-11-05T10:00:00Z");
  static final UUID BATCH_ID = UUID.fromString("019a5b2e-1c00-7000-8000-000000000001");
  static final String TXN_ID = "019a5b2e-1c00-7abc-8def-000000000002";

  static JsonNode asMvc(Object body) {
    return JSON.readTree(JSON.writeValueAsString(body));
  }

  static void assertValid(Schema schema, Object body) {
    JsonNode node = asMvc(body);
    assertThat(schema.validate(node)).as("%s", node).isEmpty();
  }

  static BatchView pendingView() {
    return BatchMapper.view(
        new Batch(
            BATCH_ID, "ORG001", BatchStatus.PENDING_APPROVAL, 2, 275050L, "BDT", "ab".repeat(32), "sub-maker",
            "org-maker01", null, null, null, AT, null));
  }

  static BatchView executingView() {
    Batch b =
        new Batch(
            BATCH_ID, "ORG001", BatchStatus.COMPLETED_WITH_EXCEPTIONS, 2, 275050L, "BDT", "ab".repeat(32), "sub-maker",
            "org-maker01", "sub-checker", "org-checker01", null, AT, AT.plusSeconds(60), BulkIds.parentTxnId(BATCH_ID),
            "01311000001", AT.plusSeconds(600));
    return BatchMapper.view(b, new BatchProgress(0, 0, 1, 0, 1, 0, new Amount(150000L, "BDT")));
  }

  static BatchView rejectedView() {
    return BatchMapper.view(
        new Batch(
            BATCH_ID, "ORG001", BatchStatus.REJECTED, 2, 275050L, "BDT", "ab".repeat(32), "sub-maker", "org-maker01",
            "sub-checker", "org-checker01", "wrong month", AT, AT.plusSeconds(60)));
  }

  static OrgTxn txn() {
    return new OrgTxn(
        "INITIATOR", TXN_ID, "BULK_DISBURSE", "COMPLETED", AT, AT.plusSeconds(2), new Amount(150000L, "BDT"),
        new Amount(0L, "BDT"), new Party("ORG", "****0001", null), new Party("WALLET", "0171****001", "CUSTOMER"),
        null, null, Map.of("parentTxnId", BulkIds.parentTxnId(BATCH_ID).toString(), "batchId", BATCH_ID.toString()));
  }

  @Test
  void batchResponsesMatchTheServedContract() {
    Schema batch = OpenApiSchemas.component(PORTAL, "Batch");
    for (BatchView v : List.of(pendingView(), executingView(), rejectedView())) {
      assertValid(batch, v);
    }
    for (String op : List.of("/{batchId}", "/{batchId}/approve", "/{batchId}/pause", "/{batchId}/resume", "/{batchId}/reject")) {
      String method = op.equals("/{batchId}") ? "get" : "post";
      assertValid(OpenApiSchemas.response(PORTAL, BATCHES + op, method, "200"), executingView());
    }
    assertValid(OpenApiSchemas.response(PORTAL, BATCHES, "post", "201"), pendingView());
    assertValid(
        OpenApiSchemas.response(PORTAL, BATCHES, "get", "200"),
        new BatchList(List.of(pendingView(), executingView(), rejectedView())));
    // the schemas really are applied: a body without the required maker is refused
    JsonNode noMaker = asMvc(pendingView());
    ((tools.jackson.databind.node.ObjectNode) noMaker).remove("makerId");
    assertThat(batch.validate(noMaker)).isNotEmpty();
  }

  @Test
  void transactionResponsesMatchTheServedContract() {
    OrgTxn failed =
        new OrgTxn(
            "PAYEE", TXN_ID, "BILL_PAY", "REJECTED", AT, AT, new Amount(5000L, "BDT"), null, null, null, null,
            new OrgTxn.Error("MW-CORE-INSUFFICIENT_FUNDS", "Insufficient balance", null, false), null);
    assertValid(OpenApiSchemas.response(PORTAL, TXNS, "get", "200"), new OrgTxn.Page(List.of(txn(), failed), 50, 0));
    OrgTxn.Detail detail =
        new OrgTxn.Detail(
                txn(),
                List.of(
                    new OrgTxn.Step("STATE", "RECEIVED", "POSTING", AT),
                    new OrgTxn.Step("CORE", null, null, AT),
                    new OrgTxn.Step("STATE", "POSTING", "COMPLETED", AT.plusSeconds(2))))
            .statesOnly();
    JsonNode body = asMvc(detail);
    assertThat(body.path("timeline")).hasSize(2);
    assertThat(body.path("timeline").get(0).has("kind")).isFalse();
    assertValid(OpenApiSchemas.response(PORTAL, TXNS + "/{txnId}", "get", "200"), detail);
  }

  @Test
  void errorEnvelopeMatchesTheServedContract() {
    assertValid(
        OpenApiSchemas.component(PORTAL, "ErrorEnvelope"),
        new ErrorEnvelope("MW-VAL-031", "2 rows are invalid", null, null, "corr-1", false,
            List.of(new ErrorDetail("line 2: msisdn", "invalid"))));
    assertValid(
        OpenApiSchemas.component(PORTAL, "ErrorEnvelope"),
        new ErrorEnvelope("MW-SYS-040", "unavailable", "unavailable bn", TXN_ID, "corr-1", true, null));
  }

  static final String TXN_JSON =
      """
      {"role":"INITIATOR","txnId":"%s","txnType":"BULK_DISBURSE","channel":"ORG_PORTAL","status":"COMPLETED",
       "previousStatus":"POSTING","createdAt":"2026-11-05T10:00:00Z","updatedAt":"2026-11-05T10:00:02Z",
       "correlationId":"bulk-1","amount":{"valueMinor":150000,"currency":"BDT"},
       "fee":{"valueMinor":0,"currency":"BDT"},"initiator":{"type":"ORG","id":"ORG001"},
       "debitParty":{"type":"ORG","id":"****0001"},
       "creditParty":{"type":"WALLET","id":"0171****001","name":"R.","walletType":"CUSTOMER"},
       "coreRef":"CORE-1","attributes":{"parentTxnId":"p","batchId":"b","lineNo":"2","reference":"SAL-NOV-001"}}
      """
          .formatted(TXN_ID);

  @Test
  void aContractShapedQueryPageReadsIntoTheClientType() {
    String page = "{\"items\":[" + TXN_JSON + "],\"limit\":50,\"offset\":0}";
    assertThat(OpenApiSchemas.component(QUERY, "TxnPage").validate(JSON.readTree(page))).isEmpty();
    OrgTxn.Page p = JSON.readValue(page, OrgTxn.Page.class);
    assertThat(p.items()).hasSize(1);
    OrgTxn t = p.items().get(0);
    assertThat(t.role()).isEqualTo("INITIATOR");
    assertThat(t.txnId()).isEqualTo(TXN_ID);
    assertThat(t.amount()).isEqualTo(new Amount(150000L, "BDT"));
    assertThat(t.creditParty().id()).isEqualTo("0171****001");
    assertThat(t.attributes()).containsEntry("reference", "SAL-NOV-001");
    // the portal view the client passes on still matches the served contract
    assertValid(OpenApiSchemas.response(PORTAL, TXNS, "get", "200"), p);
  }

  @Test
  void aContractShapedQueryDetailReadsIntoTheClientType() {
    String detail =
        "{\"transaction\":" + TXN_JSON + ",\"timeline\":["
            + "{\"kind\":\"STATE\",\"from\":\"RECEIVED\",\"to\":\"POSTING\",\"trigger\":\"TxnRequested\",\"at\":\"2026-11-05T10:00:00Z\"},"
            + "{\"kind\":\"CORE\",\"command\":\"PostTransfer\",\"commandId\":\"c1\",\"sends\":1,\"result\":\"TransferPosted\","
            + "\"outcome\":\"SUCCESS\",\"at\":\"2026-11-05T10:00:01Z\",\"resultAt\":\"2026-11-05T10:00:02Z\"},"
            + "{\"kind\":\"STATE\",\"from\":\"POSTING\",\"to\":\"COMPLETED\",\"at\":\"2026-11-05T10:00:02Z\"}],"
            + "\"partnerCalls\":[]}";
    assertThat(OpenApiSchemas.component(QUERY, "TxnDetail").validate(JSON.readTree(detail))).isEmpty();
    OrgTxn.Detail d = JSON.readValue(detail, OrgTxn.Detail.class).statesOnly();
    assertThat(d.transaction().txnId()).isEqualTo(TXN_ID);
    assertThat(d.timeline()).extracting(OrgTxn.Step::to).containsExactly("POSTING", "COMPLETED");
    assertValid(OpenApiSchemas.response(PORTAL, TXNS + "/{txnId}", "get", "200"), d);
  }
}

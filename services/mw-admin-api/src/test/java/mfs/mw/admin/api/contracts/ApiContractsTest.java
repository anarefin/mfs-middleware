package mfs.mw.admin.api.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import java.util.List;
import mfs.mw.admin.api.dto.AdminTxn;
import mfs.mw.admin.api.dto.AuditTrail;
import mfs.mw.admin.api.dto.ErrorDetail;
import mfs.mw.admin.api.dto.ErrorEnvelope;
import mfs.mw.admin.api.dto.TxnDetailView;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The APIs of {@code contracts.yml} (ticket 53). Called: contract-shaped answers of {@code
 * txn-query.v1} and {@code audit-query.v1} (validated against those contracts first) read into the
 * client types. Served: what this service builds from them, serialised as Spring MVC does, validates
 * against {@code admin.v1}.
 */
class ApiContractsTest {

  static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
  static final String ADMIN = "api/mw-admin-api/admin.v1.openapi.yaml";
  static final String QUERY = "api/mw-orchestrator/txn-query.v1.openapi.yaml";
  static final String AUDIT = "api/mw-audit-dw/audit-query.v1.openapi.yaml";
  static final String TXN_ID = "019a5b2e-1c00-7abc-8def-000000000002";

  static final String TXN_JSON =
      """
      {"txnId":"%s","txnType":"BILL_PAY","channel":"APP","status":"COMPLETED",
       "previousStatus":"PARTNER_PENDING","createdAt":"2026-11-05T10:00:00Z","updatedAt":"2026-11-05T10:00:02Z",
       "correlationId":"corr-1","amount":{"valueMinor":150000,"currency":"BDT"},
       "fee":{"valueMinor":500,"currency":"BDT"},"initiator":{"type":"MSISDN","id":"0171****001"},
       "debitParty":{"type":"WALLET","id":"0171****001","name":"R.","walletType":"CUSTOMER"},
       "creditParty":{"type":"BILLER","id":"****ESCO"},
       "partner":{"partnerId":"billagg","billerCode":"DESCO"},"partnerRef":"P-77","coreRef":"CORE-1",
       "error":{"code":"MW-PTR-BILLAGG-TIMEOUT","message":"late","retryable":true},
       "attributes":{"reference":"R1"}}
      """
          .formatted(TXN_ID);

  static final String DETAIL_JSON =
      "{\"transaction\":" + TXN_JSON + ",\"timeline\":["
          + "{\"kind\":\"STATE\",\"from\":\"RECEIVED\",\"to\":\"HOLDING\",\"trigger\":\"TxnRequested\",\"at\":\"2026-11-05T10:00:00Z\"},"
          + "{\"kind\":\"CORE\",\"command\":\"HoldFunds\",\"commandId\":\"c1\",\"sends\":2,\"result\":\"FundsHeld\","
          + "\"outcome\":\"SUCCESS\",\"at\":\"2026-11-05T10:00:01Z\",\"resultAt\":\"2026-11-05T10:00:02Z\"}],"
          + "\"partnerCalls\":[{\"requestId\":\"r1\",\"operation\":\"PAY\",\"family\":\"BILLPAY\",\"partnerId\":\"billagg\","
          + "\"sentAt\":\"2026-11-05T10:00:01Z\",\"outcome\":\"SUCCESS\",\"partnerRef\":\"P-77\",\"answeredAt\":\"2026-11-05T10:00:02Z\"}]}";

  static final String AUDIT_JSON =
      """
      {"items":[{"auditId":"0d6c3f0e-1111-4b5e-9a1e-000000000001","month":"2026-11","seq":3,
        "recordedAt":"2026-11-05T10:00:03Z","at":"2026-11-05T10:00:00Z","txnId":"%s","correlationId":"corr-1",
        "actor":{"type":"user","id":"ops-compliance01"},"action":"admin.txn.msisdn.revealed",
        "resource":"transaction/%1$s","outcome":"SUCCESS","service":"mw-admin-api","sourceIp":"10.0.0.1",
        "after":{"role":"compliance-officer","fields":["initiator.id"]}}],"limit":500,"offset":0}
      """
          .formatted(TXN_ID);

  static void assertValid(Schema schema, Object body) {
    JsonNode node = JSON.readTree(JSON.writeValueAsString(body));
    assertThat(schema.validate(node)).as("%s", node).isEmpty();
  }

  @Test
  void aContractShapedSearchPageReadsIntoTheClientTypeAndIsServedAsAdminV1() {
    String page = "{\"items\":[" + TXN_JSON + "],\"limit\":50,\"offset\":0}";
    assertThat(OpenApiSchemas.component(QUERY, "TxnPage").validate(JSON.readTree(page))).isEmpty();
    AdminTxn.Page p = JSON.readValue(page, AdminTxn.Page.class);
    AdminTxn t = p.items().get(0);
    assertThat(t.txnId()).isEqualTo(TXN_ID);
    assertThat(t.partner().billerCode()).isEqualTo("DESCO");
    assertThat(t.error().code()).isEqualTo("MW-PTR-BILLAGG-TIMEOUT");
    assertThat(t.fee()).isEqualTo(new AdminTxn.Amount(500, "BDT"));
    assertValid(OpenApiSchemas.response(ADMIN, "/admin/v1/transactions", "get", "200"), p);
  }

  @Test
  void contractShapedDetailAndAuditAnswersReadIntoTheClientTypesAndAreServedAsAdminV1() {
    assertThat(OpenApiSchemas.component(QUERY, "TxnDetail").validate(JSON.readTree(DETAIL_JSON))).isEmpty();
    assertThat(OpenApiSchemas.component(AUDIT, "AuditPage").validate(JSON.readTree(AUDIT_JSON))).isEmpty();
    AdminTxn.Detail d = JSON.readValue(DETAIL_JSON, AdminTxn.Detail.class);
    AuditTrail.Page a = JSON.readValue(AUDIT_JSON, AuditTrail.Page.class);
    assertThat(d.timeline()).extracting(AdminTxn.TimelineEntry::kind).containsExactly("STATE", "CORE");
    assertThat(d.timeline().get(1).sends()).isEqualTo(2);
    assertThat(d.partnerCalls()).extracting(AdminTxn.PartnerCall::partnerRef).containsExactly("P-77");
    assertThat(a.items().get(0).seq()).isEqualTo(3L);
    assertThat(a.items().get(0).actor().id()).isEqualTo("ops-compliance01");

    TxnDetailView view = new TxnDetailView(d.transaction(), d.timeline(), d.partnerCalls(), a.items(), false);
    Schema served = OpenApiSchemas.response(ADMIN, "/admin/v1/transactions/{txnId}", "get", "200");
    assertValid(served, view);
    // the schema really is applied: a detail without its audit trail is refused
    JsonNode noTrail = JSON.readTree(JSON.writeValueAsString(view));
    ((tools.jackson.databind.node.ObjectNode) noTrail).remove("auditTrail");
    assertThat(served.validate(noTrail)).isNotEmpty();
  }

  @Test
  void errorEnvelopeMatchesTheServedContract() {
    assertValid(
        OpenApiSchemas.component(ADMIN, "ErrorEnvelope"),
        new ErrorEnvelope("MW-AUTH-002", "Not allowed", null, null, "corr-1", false, List.of()));
    assertValid(
        OpenApiSchemas.component(ADMIN, "ErrorEnvelope"),
        new ErrorEnvelope("MW-VAL-001", "bad", null, TXN_ID, "corr-1", false, List.of(new ErrorDetail("from", "must be before to"))));
  }
}

package mfs.mw.audit.dw.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import java.time.Instant;
import java.util.List;
import mfs.mw.audit.dw.dto.AuditEntry;
import mfs.mw.audit.dw.dto.AuditEvent;
import mfs.mw.audit.dw.dto.ErrorDetail;
import mfs.mw.audit.dw.dto.ErrorEnvelope;
import mfs.mw.audit.dw.service.ChainVerification;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The API this module serves ({@code api/mw-audit-dw/audit-query.v1}, ticket 53): the response DTOs,
 * built from a fixture (the repository's row mapper builds {@code AuditEntry} field by field) and
 * serialised as Spring MVC does (the Boot-built JsonMapper), validate against the contract's
 * schemas. It calls no API.
 */
class ApiContractsTest {

  static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
  static final String API = "api/mw-audit-dw/audit-query.v1.openapi.yaml";
  static final Instant AT = Instant.parse("2026-11-05T10:00:00Z");

  static JsonNode asMvc(Object body) {
    return JSON.readTree(JSON.writeValueAsString(body));
  }

  static void assertValid(Schema schema, Object body) {
    JsonNode node = asMvc(body);
    assertThat(schema.validate(node)).as("%s", node).isEmpty();
  }

  static AuditEntry full() {
    return new AuditEntry(
        "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d", "2026-11", 42L, AT.plusMillis(5), AT,
        "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8b", "corr-1", new AuditEvent.Actor("user", "ops01"), "config.changed",
        "partner/telco-gp", "SUCCESS", "mw-admin-api", "10.0.0.7", "s3://audit/1",
        JSON.readTree("{\"status\":\"POSTING\"}"), JSON.readTree("{\"status\":\"COMPLETED\"}"));
  }

  static AuditEntry minimal() {
    return new AuditEntry(
        "1a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d", "2026-11", 1L, AT, AT, null, "corr-2",
        new AuditEvent.Actor("system", "mw-portal-api"), "bulk.batch.expired", "bulk-batch/x", "SUCCESS",
        "mw-portal-api", null, null, null, null);
  }

  @Test
  void searchPageMatchesTheContract() {
    Schema page = OpenApiSchemas.response(API, "/internal/v1/audit/events", "get", "200");
    assertValid(page, new AuditEntry.Page(List.of(full(), minimal()), 100, 0));
    assertValid(page, new AuditEntry.Page(List.of(), 1, 0));
    assertValid(OpenApiSchemas.component(API, "AuditEntry"), full());
    // the schema really is applied: an entry without its chain position is refused
    ObjectNode noSeq = (ObjectNode) asMvc(minimal());
    noSeq.remove("seq");
    assertThat(OpenApiSchemas.component(API, "AuditEntry").validate(noSeq)).isNotEmpty();
  }

  @Test
  void errorEnvelopeMatchesTheContract() {
    assertValid(
        OpenApiSchemas.component(API, "ErrorEnvelope"),
        new ErrorEnvelope("MW-VAL-001", "bad filter", null, null, "corr-1", false,
            List.of(new ErrorDetail("limit", "must be at most 500"))));
  }

  static final String TEST_API = "api/mw-audit-dw/audit-dw-test.v1.openapi.yaml";

  /** Ticket 58: the test-only search answers the same page, the chain check its verification. */
  @Test
  void testApiResponsesMatchTheirContract() {
    assertValid(
        OpenApiSchemas.response(TEST_API, "/test/v1/audit/events", "get", "200"),
        new AuditEntry.Page(List.of(full(), minimal()), 100, 0));
    Schema chain = OpenApiSchemas.response(TEST_API, "/test/v1/audit/chains/{month}", "get", "200");
    assertValid(chain, new ChainVerification("2026-11", true, 42, null, null));
    assertValid(chain, new ChainVerification("2026-11", false, 42, 7L, "hash does not match the row"));
    ObjectNode noIntact = (ObjectNode) asMvc(new ChainVerification("2026-11", true, 1, null, null));
    noIntact.remove("intact");
    assertThat(chain.validate(noIntact)).isNotEmpty();
    assertValid(
        OpenApiSchemas.component(TEST_API, "ErrorEnvelope"),
        new ErrorEnvelope("MW-SYS-002", "Chain verification is switched off", null, null, "corr-1", true, List.of()));
  }
}

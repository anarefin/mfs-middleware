package mfs.mw.crypto.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import mfs.mw.crypto.dto.CeremonyCommitRequest;
import mfs.mw.crypto.dto.CeremonyComponentRequest;
import mfs.mw.crypto.dto.CeremonyView;
import mfs.mw.crypto.dto.KeyChangeRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The ceremony and key-change parts of crypto-internal.v1 (ticket 75): what mw-admin-api sends
 * matches the request schemas, the ceremony answer the response schema.
 */
class CeremonyContractsTest {

  static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
  static final String API = "api/mw-crypto/crypto-internal.v1.openapi.yaml";
  static final Instant T0 = Instant.parse("2026-11-15T08:00:00Z");

  static void valid(Schema schema, Object body) {
    JsonNode node = JSON.readTree(JSON.writeValueAsString(body));
    assertThat(schema.validate(node)).as("%s", node).isEmpty();
  }

  @Test
  void requestsMatchTheirSchemas() {
    valid(OpenApiSchemas.component(API, "KeyChangeRequest"),
        new KeyChangeRequest(KeyChangeRequest.Action.GENERATE, "hmac-x", "HMAC_X", null, "seed-ops-secadmin1",
            "seed-ops-secadmin2"));
    valid(OpenApiSchemas.component(API, "KeyChangeRequest"),
        new KeyChangeRequest(KeyChangeRequest.Action.RETIRE, "pin-hmac", null, 1, "a", "b"));
    valid(OpenApiSchemas.component(API, "CeremonyComponentRequest"),
        new CeremonyComponentRequest("pin-hmac", "PIN_HMAC", "seed-ops-secadmin1", "ab".repeat(32), "A1B2C3"));
    valid(OpenApiSchemas.component(API, "CeremonyCommitRequest"), new CeremonyCommitRequest("a", "b"));
  }

  @Test
  void ceremonyMatchesTheResponseSchema() {
    UUID id = UUID.randomUUID();
    Schema schema = OpenApiSchemas.response(API, "/internal/v1/ceremonies/{ceremonyId}/components", "post", "200");
    valid(schema, new CeremonyView(id, "pin-hmac", "PIN_HMAC", 2, "pin-hmac.v2", "OPEN",
        List.of(new CeremonyView.Share(1, "seed-ops-secadmin1", T0)), null));
    valid(schema, new CeremonyView(id, "pin-hmac", "PIN_HMAC", 2, "pin-hmac.v2", "COMBINED",
        List.of(new CeremonyView.Share(1, "a", T0), new CeremonyView.Share(2, "b", T0)), "A1B2C3"));
    // an abort before the open leaves a tombstone without a key
    valid(OpenApiSchemas.response(API, "/internal/v1/ceremonies/{ceremonyId}", "get", "200"),
        new CeremonyView(id, null, null, null, null, "ABORTED", List.of(), null));
    valid(OpenApiSchemas.component(API, "CeremonyOpenRequest"), new mfs.mw.crypto.dto.CeremonyOpenRequest("pin-hmac", "PIN_HMAC"));
  }

  @Test
  void keyChangeStateMatchesTheContract() {
    Schema schema = OpenApiSchemas.response(API, "/internal/v1/key-changes/{changeRequestId}", "get", "200");
    valid(schema, new mfs.mw.crypto.dto.KeyChangeState(UUID.randomUUID(), false, null, null, null));
    valid(schema, new mfs.mw.crypto.dto.KeyChangeState(UUID.randomUUID(), true, "ROTATE", "pin-hmac", "pin-hmac.v2"));
  }

  @Test
  void theComponentNeverRendersInADtoString() {
    String component = "ab".repeat(32);
    assertThat(new CeremonyComponentRequest("pin-hmac", "PIN_HMAC", "a", component, "A1B2C3").toString())
        .doesNotContain(component);
  }
}

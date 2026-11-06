package mfs.mw.seeder.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import java.io.InputStream;
import java.util.List;
import mfs.mw.seeder.config.SeederConfig;
import mfs.mw.seeder.dto.ChangeRequestView;
import mfs.mw.seeder.dto.ContentVersionView;
import mfs.mw.seeder.dto.MenuProposal;
import mfs.mw.seeder.dto.PartnerChangeRequest;
import mfs.mw.seeder.dto.PartnerView;
import mfs.mw.seeder.dto.TemplateProposal;
import mfs.mw.seeder.dto.WalletSeedRequest;
import mfs.mw.seeder.dto.WalletView;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The APIs of {@code contracts.yml} (ticket 57): every request body the seeder sends, serialised by
 * its own mapper, validates against the OpenAPI request schema; contract-shaped answers (validated
 * against the contract first) read into the seeder's types.
 */
class ConsumedContractsTest {

  static final JsonMapper JSON = SeederConfig.jsonMapper();
  static final String ADMIN = "api/mw-admin-api/admin.v1.openapi.yaml";
  static final String CORE = "api/mw-core-mock/core-mock-seed.v1.openapi.yaml";

  static void assertValid(Schema schema, Object body) {
    JsonNode node = JSON.readTree(JSON.writeValueAsString(body));
    assertThat(schema.validate(node)).as("%s", node).isEmpty();
  }

  @Test
  void theManifestListsExactlyTheseApis() {
    ContractManifest m = ContractManifest.load();
    assertThat(m.consumedApis()).containsExactlyInAnyOrder(ADMIN, CORE);
    assertThat(m.producedApis()).isEmpty();
    assertThat(m.producedEvents()).isEmpty();
    assertThat(m.consumedEvents()).isEmpty();
  }

  @Test
  void partnerChangeRequestsAreAdminV1Bodies() {
    Schema s = OpenApiSchemas.request(ADMIN, "/admin/v1/partners/{partnerId}/change-requests", "post");
    assertValid(s, PartnerChangeRequest.of("ENABLE", "seed"));
    assertValid(s, PartnerChangeRequest.of("DISABLE", "seed"));
    assertValid(s, PartnerChangeRequest.timeout(20_000, "seed"));
  }

  @Test
  void theSeededMenuIsAnAdminV1MenuProposal() throws Exception {
    ObjectNode tree;
    try (InputStream in = getClass().getResourceAsStream("/seed-content/menus/main.json")) {
      tree = (ObjectNode) JSON.readTree(in);
    }
    tree.remove("menuId");
    tree.remove("version");
    MenuProposal p =
        new MenuProposal(tree, List.of(new MenuProposal.Activation("gp"), new MenuProposal.Activation("teletalk")), "seed");
    assertValid(OpenApiSchemas.request(ADMIN, "/admin/v1/ussd-menus/{menuId}/change-requests", "post"), p);
  }

  @Test
  void templateProposalsAreAdminV1BodiesWithoutNullFields() {
    Schema s = OpenApiSchemas.request(ADMIN, "/admin/v1/notify-templates/{code}/{channel}/{locale}/change-requests", "post");
    TemplateProposal sms = new TemplateProposal("You have received Tk {{amount}}", null, "Bits", "seed");
    assertValid(s, sms);
    assertThat(JSON.writeValueAsString(sms)).doesNotContain("subject");
  }

  @Test
  void theWalletSeedBodyIsTheCoreMockContractsAndItsAnswerReads() {
    assertValid(OpenApiSchemas.request(CORE, "/core-mock/v1/wallets/{walletId}", "put"), new WalletSeedRequest("ORG", 100_000_000));
    String answer =
        "{\"walletId\":\"01712000001\",\"type\":\"CUSTOMER\",\"status\":\"ACTIVE\",\"balanceMinor\":10,\"heldMinor\":2,\"availableMinor\":8}";
    assertThat(OpenApiSchemas.response(CORE, "/core-mock/v1/wallets/{walletId}", "put", "201").validate(JSON.readTree(answer))).isEmpty();
    assertThat(JSON.readValue(answer, WalletView.class))
        .isEqualTo(new WalletView("01712000001", "CUSTOMER", "ACTIVE", 10, 2, 8));
  }

  @Test
  void adminAnswersReadIntoTheSeedersTypes() {
    String partner =
        """
        {"partnerId":"ocs","family":"telco","version":3,"updatedAt":"2026-11-06T00:00:00Z","updatedBy":"seed-ops-maker",
         "config":{"enabled":false,"baseUrl":"http://mw-simulators:8080","timeoutMs":20000,
           "operations":{"topup":{"method":"POST","path":"/ocs/v1/topups","response":"$"}}}}
        """;
    assertThat(OpenApiSchemas.component(ADMIN, "Partner").validate(JSON.readTree(partner))).isEmpty();
    PartnerView p = JSON.readValue(partner, PartnerView.class);
    assertThat(p.config().isEnabled()).isFalse();
    assertThat(p.config().timeoutMs()).isEqualTo(20_000);
    assertThat(p.version()).isEqualTo(3);

    String cr =
        """
        {"id":"0d6c3f0e-1111-4b5e-9a1e-000000000001","resourceType":"partner","resourceId":"ocs","action":"ENABLE",
         "payload":{},"diff":{"before":{},"after":{}},"makerId":"seed-ops-maker","status":"PENDING","reason":"seed",
         "createdAt":"2026-11-06T00:00:00Z","expiresAt":"2026-11-09T00:00:00Z"}
        """;
    String page = "{\"items\":[" + cr + "],\"limit\":200,\"offset\":0}";
    assertThat(OpenApiSchemas.component(ADMIN, "ChangeRequestPage").validate(JSON.readTree(page))).isEmpty();
    ChangeRequestView v = JSON.readValue(page, ChangeRequestView.Page.class).items().getFirst();
    assertThat(v.makerId()).isEqualTo("seed-ops-maker");
    assertThat(v.status()).isEqualTo("PENDING");

    String versions =
        """
        {"items":[{"configKey":"ussd-menu/main","version":2,"status":"PUBLISHED",
          "changeRequestId":"0d6c3f0e-1111-4b5e-9a1e-000000000001","makerId":"seed-ops-maker",
          "createdAt":"2026-11-06T00:00:00Z"}],"limit":200,"offset":0}
        """;
    assertThat(OpenApiSchemas.component(ADMIN, "ContentVersionPage").validate(JSON.readTree(versions))).isEmpty();
    assertThat(JSON.readValue(versions, ContentVersionView.Page.class).items().getFirst().version()).isEqualTo(2);
  }
}

package mfs.mw.adapter.telco.partner.route;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import mfs.mw.adapter.telco.TestInfrastructure;
import mfs.mw.adapter.telco.partner.PartnerCall;
import mfs.mw.adapter.telco.partner.PartnerResponse;
import mfs.mw.adapter.telco.partner.config.PartnerConfigStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Ticket 112a: each operator OCS seed maps a canonical call onto that operator's recorded wire
 * payload and maps the recorded error body through the record's error map. {@code ocs} stays the
 * legacy alias of the channel operator ids.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class OperatorOcsRouteIT {

  static final String REF = "BITS112A";
  static final String TXN = "01933b8e-7c1a-7b2e-9f3d-000000000112";
  static final WireMockServer OCS = new WireMockServer(WireMockConfiguration.options().dynamicPort());

  static {
    OCS.start();
  }

  @DynamicPropertySource
  static void simulator(DynamicPropertyRegistry registry) {
    registry.add("OCS_BASE_URL", () -> "http://localhost:" + OCS.port());
  }

  private final PartnerGateway gateway;
  private final PartnerConfigStore store;

  @Autowired
  OperatorOcsRouteIT(PartnerGateway gateway, PartnerConfigStore store) {
    this.gateway = gateway;
    this.store = store;
  }

  @BeforeEach
  void reset() {
    OCS.resetAll();
    OCS.stubFor(post(urlEqualTo("/ocs-teletalk/oauth/token"))
        .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
            .withBody("{\"access_token\":\"dev-route-token\",\"token_type\":\"bearer\",\"expires_in\":3600}")));
  }

  static java.util.List<Op> operators() {
    return java.util.List.of(
        new Op("gp-ocs", "01711000021", "API_KEY", "/ocs-gp/v1/recharge", "/ocs-gp/v1/billpay", "/ocs-gp/v1/bills",
            "{\"clientTxnId\":\"BITS112A\",\"subscriber\":\"01711000021\",\"rechargeAmount\":\"20.00\",\"connection\":\"PREPAID\"}",
            "{\"clientTxnId\":\"BITS112A\",\"subscriber\":\"01711000021\",\"rechargeAmount\":\"20.00\",\"connection\":\"POSTPAID\"}",
            "{\"subscriber\":\"01711000021\"}",
            422, "{\"status\":\"FAILED\",\"code\":\"GP1001\",\"message\":\"Subscriber barred\"}", "SUBSCRIBER_BARRED",
            "{\"status\":\"SUCCESS\",\"gpRef\":\"GP-778899\",\"code\":\"GP000\"}", "GP-778899",
            "{\"status\":\"DUE\",\"billRef\":\"GPB-01711000021\",\"outstanding\":\"450.00\",\"minimum\":\"50.00\",\"code\":\"GP000\"}",
            "GPB-01711000021", "450.00", "50.00"),
        new Op("robi-ocs", "01811000021", "HMAC", "/ocs-robi/v1/topup", "/ocs-robi/v1/settle", "/ocs-robi/v1/invoice",
            "{\"ref\":\"BITS112A\",\"msisdn\":\"01811000021\",\"amt\":\"20.00\",\"kind\":\"PRE\"}",
            "{\"ref\":\"BITS112A\",\"msisdn\":\"01811000021\",\"amt\":\"20.00\",\"kind\":\"POST\"}",
            "{\"msisdn\":\"01811000021\"}",
            400, "{\"ok\":false,\"rc\":\"RB77\",\"text\":\"Barred\"}", "BARRED",
            "{\"ok\":true,\"robiTxn\":\"RB-778899\",\"rc\":\"0\"}", "RB-778899",
            "{\"ok\":true,\"invoice\":\"RBB-01811000021\",\"due\":\"320.50\",\"minPay\":\"20.00\",\"rc\":\"0\"}",
            "RBB-01811000021", "320.50", "20.00"),
        new Op("teletalk-ocs", "01511000021", "OAUTH", "/ocs-teletalk/v1/recharge", "/ocs-teletalk/v1/pay", "/ocs-teletalk/v1/bill",
            "{\"transactionRef\":\"BITS112A\",\"mobile\":\"01511000021\",\"taka\":\"20.00\",\"line\":\"prepaid\"}",
            "{\"transactionRef\":\"BITS112A\",\"mobile\":\"01511000021\",\"taka\":\"20.00\",\"line\":\"postpaid\"}",
            "{\"mobile\":\"01511000021\"}",
            422, "{\"accepted\":false,\"errCode\":\"TT09\",\"errText\":\"Not eligible\"}", "INELIGIBLE",
            "{\"accepted\":true,\"ttId\":\"TT-778899\",\"errCode\":\"00\"}", "TT-778899",
            "{\"accepted\":true,\"billId\":\"TTB-01511000021\",\"outstandingTaka\":\"180.00\",\"minimumTaka\":\"10.00\",\"errCode\":\"00\"}",
            "TTB-01511000021", "180.00", "10.00"),
        new Op("bl-ocs", "01911000021", "BASIC", "/ocs-bl/v1/reload", "/ocs-bl/v1/paybill", "/ocs-bl/v1/due",
            "{\"id\":\"BITS112A\",\"number\":\"01911000021\",\"value\":\"20.00\",\"prepaid\":true}",
            "{\"id\":\"BITS112A\",\"number\":\"01911000021\",\"value\":\"20.00\",\"prepaid\":false}",
            "{\"number\":\"01911000021\"}",
            403, "{\"outcome\":\"NOK\",\"faultCode\":\"BL42\",\"faultText\":\"Rejected\"}", "REJECTED",
            "{\"outcome\":\"OK\",\"blId\":\"BL-778899\",\"faultCode\":\"0\"}", "BL-778899",
            "{\"outcome\":\"DUE\",\"billId\":\"BLB-01911000021\",\"dueAmount\":\"275.75\",\"minAmount\":\"25.00\",\"faultCode\":\"0\"}",
            "BLB-01911000021", "275.75", "25.00"));
  }

  @Test
  void theChannelOperatorIdsStayOnTheLegacyOcsAlias() {
    assertThat(store.resolve("gp").orElseThrow().id()).isEqualTo("ocs");
    assertThat(store.resolve("robi").orElseThrow().id()).isEqualTo("ocs");
    assertThat(store.resolve("banglalink").orElseThrow().id()).isEqualTo("ocs");
    assertThat(store.resolve("teletalk").orElseThrow().id()).isEqualTo("ocs");
    for (Op op : operators()) {
      assertThat(store.instance(op.id())).as(op.id()).isPresent();
    }
  }

  @ParameterizedTest
  @MethodSource("operators")
  void aPrepaidPayRoundTripsTheRecordedPayloadAndSendsThatOperatorsAuth(Op op) {
    stub(op.payPath(), 200, op.successBody());

    PartnerResponse response = gateway.call(call(op.id(), "PAY", op.msisdn(), "PREPAID"));

    assertThat(response).isInstanceOf(PartnerResponse.Success.class);
    PartnerResponse.Success success = (PartnerResponse.Success) response;
    assertThat(success.partnerRef()).isEqualTo(op.partnerRef());
    OCS.verify(postRequestedFor(urlEqualTo(op.payPath())).withRequestBody(equalToJson(op.payBody())));
    assertAuth(op);
  }

  @ParameterizedTest
  @MethodSource("operators")
  void aRecordedRejectionMapsThroughThatOperatorsErrorMap(Op op) {
    stub(op.payPath(), op.errorStatus(), op.errorBody());

    PartnerResponse response = gateway.call(call(op.id(), "PAY", op.msisdn(), "PREPAID"));

    assertThat(response).isInstanceOf(PartnerResponse.Rejected.class);
    PartnerResponse.Rejected rejected = (PartnerResponse.Rejected) response;
    assertThat(rejected.errorSuffix()).isEqualTo(op.errorSuffix());
    assertThat(rejected.retryable()).isFalse();
  }

  @ParameterizedTest
  @MethodSource("operators")
  void aPostpaidPayUsesThePostpaidPayload(Op op) {
    stub(op.postpaidPath(), 200, op.successBody());

    PartnerResponse response = gateway.call(call(op.id(), "PAY", op.msisdn(), "POSTPAID"));

    assertThat(response).isInstanceOf(PartnerResponse.Success.class);
    OCS.verify(postRequestedFor(urlEqualTo(op.postpaidPath())).withRequestBody(equalToJson(op.postpaidBody())));
  }

  @ParameterizedTest
  @MethodSource("operators")
  void aBillFetchReturnsTheOutstandingAmountAndTheMinimum(Op op) {
    stub(op.billPath(), 200, op.billBody());

    PartnerResponse response = gateway.call(call(op.id(), "INQUIRY", op.msisdn(), "POSTPAID"));

    assertThat(response).isInstanceOf(PartnerResponse.Success.class);
    PartnerResponse.Success success = (PartnerResponse.Success) response;
    assertThat(success.partnerRef()).isEqualTo(op.billRef());
    assertThat(success.attributes()).containsEntry("outstanding", op.outstanding()).containsEntry("minimum", op.minimum());
    OCS.verify(postRequestedFor(urlEqualTo(op.billPath())).withRequestBody(equalToJson(op.billRequest())));
  }

  private void assertAuth(Op op) {
    var requested = postRequestedFor(urlEqualTo(op.payPath()));
    switch (op.auth()) {
      case "API_KEY" -> OCS.verify(requested.withHeader("X-GP-Api-Key", equalTo("fake-gp-ocs-api-key-for-tests")));
      case "HMAC" -> OCS.verify(requested.withHeader("X-Key-Id", equalTo("robi-ocs")).withHeader("X-Signature", matching(".+")));
      case "OAUTH" -> {
        OCS.verify(requested.withHeader("Authorization", equalTo("Bearer dev-route-token")));
        OCS.verify(postRequestedFor(urlEqualTo("/ocs-teletalk/oauth/token")));
      }
      case "BASIC" -> {
        String basic = Base64.getEncoder().encodeToString(
            "fake-bl-ocs-basic-user-for-tests:fake-bl-ocs-basic-password-for-tests".getBytes(StandardCharsets.UTF_8));
        OCS.verify(requested.withHeader("Authorization", equalTo("Basic " + basic)));
      }
      default -> throw new IllegalStateException(op.auth());
    }
  }

  private static void stub(String path, int status, String body) {
    OCS.stubFor(post(urlEqualTo(path)).willReturn(aResponse().withStatus(status)
        .withHeader("Content-Type", "application/json").withBody(body)));
  }

  private static PartnerCall call(String partner, String operation, String msisdn, String product) {
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("amount", Map.of("valueMinor", 2000L, "currency", "BDT", "major", "20.00"));
    input.put("creditParty", Map.of("type", "MSISDN", "id", msisdn));
    input.put("partner", Map.of("partnerId", partner, "productCode", product));
    return new PartnerCall(partner, operation, "TOPUP", TXN, "corr-112a", REF, input);
  }

  record Op(
      String id,
      String msisdn,
      String auth,
      String payPath,
      String postpaidPath,
      String billPath,
      String payBody,
      String postpaidBody,
      String billRequest,
      int errorStatus,
      String errorBody,
      String errorSuffix,
      String successBody,
      String partnerRef,
      String billBody,
      String billRef,
      String outstanding,
      String minimum) {}
}

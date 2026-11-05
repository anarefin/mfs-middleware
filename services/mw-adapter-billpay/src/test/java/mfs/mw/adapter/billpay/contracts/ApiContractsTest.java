package mfs.mw.adapter.billpay.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.networknt.schema.Schema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.adapter.billpay.config.BillerProperties;
import mfs.mw.adapter.billpay.dto.BillView;
import mfs.mw.adapter.billpay.dto.BillerView;
import mfs.mw.adapter.billpay.dto.ErrorEnvelope;
import mfs.mw.adapter.billpay.dto.ErrorDetail;
import mfs.mw.adapter.billpay.exception.MwException;
import mfs.mw.adapter.billpay.mapper.PartnerCallMapper;
import mfs.mw.adapter.billpay.partner.PartnerCall;
import mfs.mw.adapter.billpay.partner.PartnerResponse;
import mfs.mw.adapter.billpay.partner.template.PartnerGateway;
import mfs.mw.adapter.billpay.service.BillFetchService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The API this module serves ({@code produces.apis}: {@code billpay.v1}, ticket 53): response
 * bodies built by the real {@link BillFetchService} from fixtures, serialised with the service's
 * JSON mapper as Spring MVC writes them, validated against the OpenAPI component schemas. The
 * partner gateway is stubbed (no Camel, no HTTP); no Spring context, no Docker.
 */
class ApiContractsTest {

  static final String API = "api/mw-adapter-billpay/billpay.v1.openapi.yaml";

  static final BillerProperties BILLERS =
      new BillerProperties(
          new LinkedHashMap<>(
              Map.of(
                  "DESCO", new BillerProperties.Biller("Dhaka Electric Supply Company", "ELECTRICITY", "billagg"),
                  "MISC", new BillerProperties.Biller("Biller without a category", null, "billagg"))));

  static BillFetchService service(PartnerResponse answer) {
    PartnerGateway gateway =
        new PartnerGateway(null, null) {
          @Override
          public PartnerResponse call(PartnerCall call) {
            return answer;
          }
        };
    return new BillFetchService(BILLERS, new PartnerCallMapper(), gateway);
  }

  static JsonNode json(Object body) {
    return ServiceJson.MAPPER.readTree(ServiceJson.MAPPER.writeValueAsString(body));
  }

  @Test
  void theManifestListsThisApi() {
    assertThat(ContractManifest.load().producedApis()).containsExactly(API);
    assertThat(ContractManifest.load().consumedApis()).isEmpty();
  }

  @Test
  void theBillerListMatchesTheContract() {
    List<BillerView> billers = service(null).billers();
    assertThat(billers).hasSize(2);

    Schema biller = OpenApiSchemas.component(API, "Biller");
    for (JsonNode b : json(billers)) {
      assertThat(biller.validate(b)).as("%s", b).isEmpty();
    }
    // the 200 response of listBillers is an array of Biller
    assertThat(OpenApiSchemas.document(API).at("/paths/~1billpay~1v1~1billers/get/responses/200/content/application~1json/schema/items/$ref").asString())
        .isEqualTo("#/components/schemas/Biller");
  }

  @Test
  void aFetchedBillMatchesTheContract() {
    BillView full =
        service(new PartnerResponse.Success("AGG-1", "000", Map.of("amount", "667.00", "dueDate", "2026-11-14", "billPeriod", "2026-11")))
            .fetch("DESCO", "1234567");
    BillView bare = service(new PartnerResponse.Success("AGG-1", "000", Map.of("amount", "0"))).fetch("DESCO", "1234567");

    Schema bill = OpenApiSchemas.component(API, "Bill");
    assertThat(bill.validate(json(full))).as("%s", json(full)).isEmpty();
    assertThat(bill.validate(json(bare))).as("%s", json(bare)).isEmpty();
  }

  /**
   * Every error the service throws, as {@code GlobalExceptionHandler.respond} turns it into the
   * envelope (code, message, messageBn, txnId, correlation id of the request, retryable, details).
   */
  @Test
  void errorEnvelopesMatchTheContract() {
    List<MwException> errors =
        List.of(
            catchThrowableOfType(MwException.class, () -> service(null).fetch("NOPE", "1")),
            catchThrowableOfType(MwException.class, () -> service(new PartnerResponse.Rejected("3001", "No bill", "BILL_NOT_FOUND", false)).fetch("DESCO", "1")),
            catchThrowableOfType(MwException.class, () -> service(new PartnerResponse.NotSent(PartnerResponse.NotSentReason.BULKHEAD_FULL)).fetch("DESCO", "1")),
            catchThrowableOfType(MwException.class, () -> service(new PartnerResponse.Unknown(PartnerResponse.UnknownReason.TIMEOUT)).fetch("DESCO", "1")),
            catchThrowableOfType(MwException.class, () -> service(new PartnerResponse.Success("AGG-1", "000", Map.of("amount", "x"))).fetch("DESCO", "1")),
            new MwException("MW-VAL-001", "Request validation failed").withDetails(List.of(new ErrorDetail("billNumber", "must match"))));

    Schema envelope = OpenApiSchemas.component(API, "ErrorEnvelope");
    for (MwException e : errors) {
      ErrorEnvelope body =
          new ErrorEnvelope(e.code(), e.getMessage(), e.messageBn(), e.txnId(), "corr-0002", e.retryable(), e.details());
      assertThat(envelope.validate(json(body))).as("%s", json(body)).isEmpty();
    }
  }
}

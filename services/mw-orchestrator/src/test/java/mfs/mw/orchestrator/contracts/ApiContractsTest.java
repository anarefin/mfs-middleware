package mfs.mw.orchestrator.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import mfs.mw.orchestrator.TestData;
import mfs.mw.orchestrator.dto.ErrorDetail;
import mfs.mw.orchestrator.dto.ErrorEnvelope;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.dto.TxnDetail;
import mfs.mw.orchestrator.dto.TxnPage;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.exception.ErrorCodes;
import mfs.mw.orchestrator.exception.MwException;
import mfs.mw.orchestrator.mapper.RequestMapper;
import mfs.mw.orchestrator.mapper.TransactionViewMapper;
import mfs.mw.orchestrator.service.saga.Status;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The API this module serves ({@code produces.apis}: {@code txn-query.v1}): the response bodies of
 * {@code controller/TransactionQueryController}, built from fixtures through the real view mapper
 * and serialised with the service JsonMapper (the one Spring MVC uses), validate against the
 * OpenAPI component schemas. The module calls no API ({@code consumes.apis} is empty).
 */
class ApiContractsTest {

  private static final String API = "api/mw-orchestrator/txn-query.v1.openapi.yaml";
  private static final String TEST_API = "api/mw-orchestrator/orchestrator-test.v1.openapi.yaml";
  private static final JsonMapper JSON = ContractManifest.serviceJsonMapper();
  private static final RequestMapper REQUESTS = new RequestMapper(JSON);

  private static JsonNode body(Object response) {
    return JSON.readTree(JSON.writeValueAsString(response));
  }

  private static TransactionRecord topupFailed() {
    var req = TestData.topup(TestData.txnId(), "key-q", 2_000, "01711000002");
    return REQUESTS.toRecord(req, null, TestData.NOW)
        .moved(Status.VALIDATING, TestData.NOW, null)
        .moved(Status.HOLDING, TestData.NOW, null)
        .withHold("HOLD-1")
        .moved(Status.PARTNER_PENDING, TestData.NOW, null)
        .withPartnerRef("GP-REF-1")
        .moved(Status.RELEASING, TestData.NOW, new ErrorInfo("MW-PTR-GP-17", "Partner rejected 01711000002", null, false))
        .moved(Status.FAILED, TestData.NOW.plusSeconds(2), new ErrorInfo("MW-PTR-GP-17", "Partner rejected", "অংশীদার প্রত্যাখ্যান করেছে", false));
  }

  @Test
  void theApiIsTheOneTheManifestSaysThisModuleServes() {
    assertThat(ContractManifest.entries("produces", "apis")).containsExactly(API, TEST_API);
    assertThat(ContractManifest.entries("consumes", "apis")).isEmpty();
  }

  @Test
  void aSearchPageMatchesTxnPage() {
    TransactionRecord bulk =
        REQUESTS.toRecord(TestData.bulkChild(TestData.txnId(), "key-b", 10_000, "01711000003"), null, TestData.NOW);
    TxnPage page = new TxnPage(
        List.of(TransactionViewMapper.toView(topupFailed()), TransactionViewMapper.toView(bulk, "INITIATOR")), 50, 0);

    assertThat(OpenApiSchemas.component(API, "TxnPage").validate(body(page))).isEmpty();
  }

  @Test
  void aDetailWithTimelineAndPartnerCallsMatchesTxnDetail() {
    TxnDetail detail = new TxnDetail(
        TransactionViewMapper.toView(topupFailed()),
        List.of(
            new TxnDetail.TimelineEntry(TxnDetail.STATE, null, "RECEIVED", "Created", null, null, null, null, null, null, TestData.NOW, null),
            new TxnDetail.TimelineEntry(TxnDetail.CORE, null, null, null, "HoldFunds", "x:hold", 2, "FundsHeld", "SUCCESS", null, TestData.NOW, TestData.NOW.plusSeconds(1)),
            new TxnDetail.TimelineEntry(TxnDetail.STATE, "HOLDING", "PARTNER_PENDING", "HoldPlaced", null, null, null, null, null, null, TestData.NOW, null)),
        List.of(
            new TxnDetail.PartnerCallView("req-1", "PAY", "telco", "gp", TestData.NOW, "FAILED", "GP-REF-1", "MW-PTR-GP-17", TestData.NOW.plusSeconds(1)),
            new TxnDetail.PartnerCallView("req-2", "STATUS_QUERY", "telco", "gp", TestData.NOW, null, null, null, null)));

    assertThat(OpenApiSchemas.component(API, "TxnDetail").validate(body(detail))).isEmpty();
  }

  /** As GlobalExceptionHandler builds it for the 404 of TransactionQueryService. */
  @Test
  void theNotFoundErrorMatchesErrorEnvelope() {
    MwException e = new MwException(ErrorCodes.VAL_NOT_FOUND, "Transaction not found")
        .withDetails(List.of(new ErrorDetail("txnId", "not found")));
    ErrorEnvelope envelope = new ErrorEnvelope(
        e.code(), e.getMessage(), e.messageBn(), e.txnId(), "corr-1", e.retryable(), e.details());

    assertThat(OpenApiSchemas.component(API, "ErrorEnvelope").validate(body(envelope))).isEmpty();
  }

  /** The test-only API (ticket 58, controller/TestApiController). */
  @Test
  void theTestApiBodiesMatchTheirSchemas() {
    var accepted = new mfs.mw.orchestrator.dto.TestReversalAccepted(TestData.txnId(), "test-rev-" + TestData.txnId());
    var handled = new mfs.mw.orchestrator.dto.TxnRequestHandled("test-rev-1", true);

    assertThat(OpenApiSchemas.component(TEST_API, "ReversalAccepted").validate(body(accepted))).isEmpty();
    assertThat(OpenApiSchemas.component(TEST_API, "TxnRequestHandled").validate(body(handled))).isEmpty();
    assertThat(OpenApiSchemas.component(TEST_API, "TxnRequestHandled")
        .validate(body(new mfs.mw.orchestrator.dto.TxnRequestHandled("k", false)))).isNotEmpty();
  }

  /** Guards the helper: a body that breaks the schema is reported. */
  @Test
  void aPageWithoutItemsIsRejected() {
    assertThat(OpenApiSchemas.component(API, "TxnPage").validate(body(Map.of("limit", 50, "offset", 0)))).isNotEmpty();
  }
}

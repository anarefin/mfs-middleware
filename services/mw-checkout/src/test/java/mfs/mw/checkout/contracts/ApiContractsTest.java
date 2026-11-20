package mfs.mw.checkout.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import mfs.mw.checkout.dto.Amount;
import mfs.mw.checkout.dto.ErrorDetail;
import mfs.mw.checkout.dto.ErrorEnvelope;
import mfs.mw.checkout.dto.ErrorInfo;
import mfs.mw.checkout.dto.MerchantSessionView;
import mfs.mw.checkout.dto.OtpSent;
import mfs.mw.checkout.dto.PageView;
import mfs.mw.checkout.dto.PayResult;
import mfs.mw.checkout.dto.SessionCreated;
import mfs.mw.checkout.dto.SessionStatus;
import mfs.mw.checkout.entity.CheckoutSession;
import mfs.mw.checkout.mapper.IpnMapper;
import mfs.mw.checkout.security.ResultSigner;
import mfs.mw.checkout.util.MaskingPolicy;
import mfs.mw.checkout.util.SensitiveType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * API contract of {@code contracts.yml}: the response bodies of the checkout API this module
 * serves ({@code api/mw-checkout/checkout.v1.openapi.yaml}: merchant and hosted page endpoints),
 * built from fixtures as the services build them and serialised with the service's JsonMapper
 * (as Spring MVC writes them), validate against the OpenAPI component schemas; so does the IPN
 * body (webhook {@code ipn}, schema {@code Ipn}) exactly as {@link IpnMapper} stores it for
 * signing and sending. The module calls no other module's API ({@code consumes.apis} is empty:
 * the merchant's IPN endpoint is described by this module's own contract).
 */
class ApiContractsTest {

  static final JsonMapper JSON = ServiceJson.MAPPER;
  static final String API = "api/mw-checkout/checkout.v1.openapi.yaml";
  static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");
  static final UUID SESSION = UUID.fromString("019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c01");
  static final UUID TXN_ID = UUID.fromString("019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0d");
  static final String CORRELATION_ID = "019a4f6e-0000-7a3b-8c4d-5e6f7a8b9c0d";

  static CheckoutSession session(SessionStatus status, UUID txnId, String errorCode) {
    return new CheckoutSession(
        SESSION, "tok", "merchant_demo", "01911000001", "ORD-42", 2550, "BDT", "Order 42", "en",
        "https://shop.example.test/ok", "https://shop.example.test/fail", "https://shop.example.test/cancel",
        "https://shop.example.test/ipn", status, "01711000001",
        1, txnId, errorCode, NOW, NOW.plusSeconds(900), NOW.plusSeconds(30));
  }

  static void assertValid(String component, Object body) {
    JsonNode tree = JSON.readTree(JSON.writeValueAsString(body));
    assertThat(OpenApiSchemas.component(API, component).validate(tree)).as("%s: %s", component, tree).isEmpty();
  }

  static String redirect(CheckoutSession s, SessionStatus status) {
    return ResultSigner.redirectUrl(s.returnUrlFor(status), "fake-merchant-secret",
        new ResultSigner.Result(s.sessionId().toString(), s.orderId(), status.name(),
            s.txnId() == null ? null : s.txnId().toString(), s.amountMinor(), s.currency(),
            status == SessionStatus.FAILED ? s.errorCode() : null, NOW.getEpochSecond()))
        .toString();
  }

  @Test
  void theManifestListsThisApi() {
    ContractManifest manifest = ContractManifest.load();
    assertThat(manifest.producedApis()).containsExactly(API);
    assertThat(manifest.consumedApis()).containsExactlyInAnyOrder(AUTH_INTERNAL);
  }

  static final String AUTH_INTERNAL = "api/mw-auth/auth-internal.v1.openapi.yaml";

  static void assertValidAt(String api, String component, Object value) {
    tools.jackson.databind.JsonNode tree = ServiceJson.MAPPER.readTree(ServiceJson.MAPPER.writeValueAsString(value));
    assertThat(OpenApiSchemas.component(api, component).validate(tree)).as("%s %s: %s", api, component, tree).isEmpty();
  }

  @Test
  void mwAuthRequestsAndAnswersMatchItsContracts() {
    // client/AuthClient: what this service sends ...
    assertValidAt(AUTH_INTERNAL, "ChannelOtpRequest",
        new mfs.mw.checkout.dto.AuthOtpRequest("01711000001", "CHECKOUT", SESSION.toString(), "bn", "25.50", "Karim Traders"));
    assertValidAt(AUTH_INTERNAL, "ChannelOtpVerification",
        new mfs.mw.checkout.dto.AuthOtpVerification("01711000001", "CHECKOUT", SESSION.toString(), "123456"));
    // ... and the answers it reads into its own types
    assertValidAt(AUTH_INTERNAL, "ChannelOtpSent", new OtpSent("0171****001", NOW.plusSeconds(120), 4));
    assertValidAt(AUTH_INTERNAL, "TxnAuthorisation", new mfs.mw.checkout.dto.TxnAuthorisation(
        "eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiIwMTcxMTAwMDAwMSJ9.c2lnbmF0dXJl", NOW.plusSeconds(60), false));
    assertValidAt(AUTH_INTERNAL, "ChannelPinKey", new mfs.mw.checkout.dto.ChannelPinKey(
        "pin-transport.v1", "RSA-OAEP-SHA-256", "-----BEGIN PUBLIC KEY-----\nMIIB\n-----END PUBLIC KEY-----\n",
        "PIN_TRANSPORT"));
  }

  @Test
  void merchantResponsesMatchTheContract() {
    // MerchantSessionService.created / find
    assertValid("SessionCreated",
        new SessionCreated(SESSION.toString(), "https://pay.example.test/checkout/tok", SessionStatus.OPEN,
            NOW.plusSeconds(900)));
    CheckoutSession open = session(SessionStatus.OPEN, null, null);
    assertValid("MerchantSessionView",
        new MerchantSessionView(open.sessionId().toString(), open.orderId(), open.status(),
            new Amount(open.amountMinor(), open.currency()), null, null, null,
            open.createdAt(), open.expiresAt(), open.updatedAt()));
    CheckoutSession failed = session(SessionStatus.FAILED, TXN_ID, "MW-CORE-INSUFFICIENT_BALANCE");
    assertValid("MerchantSessionView",
        new MerchantSessionView(failed.sessionId().toString(), failed.orderId(), failed.status(),
            new Amount(failed.amountMinor(), failed.currency()), failed.txnId().toString(),
            MaskingPolicy.mask(SensitiveType.MSISDN, failed.payerMsisdn()), failed.errorCode(),
            failed.createdAt(), failed.expiresAt(), failed.updatedAt()));
    // MerchantSessionService.list (ticket 116)
    assertValid("PaymentList", new mfs.mw.checkout.dto.PaymentList(java.util.List.of(
        new MerchantSessionView(open.sessionId().toString(), open.orderId(), open.status(),
            new Amount(open.amountMinor(), open.currency()), null, null, null,
            open.createdAt(), open.expiresAt(), open.updatedAt())), 0, 50, false));
  }

  @Test
  void hostedPageResponsesMatchTheContract() {
    // CheckoutPayService.page / sendOtp / confirm / cancel
    CheckoutSession paid = session(SessionStatus.PAID, TXN_ID, null);
    assertValid("PageView",
        new PageView("Karim Traders", "ORD-42", "Order 42", new Amount(2550L, "BDT"), SessionStatus.OPEN,
            NOW.plusSeconds(900), null,
            new PageView.PinKey("pin-transport.v1", "RSA-OAEP-SHA-256", "-----BEGIN PUBLIC KEY-----"), null, null));
    assertValid("PageView",
        new PageView("Karim Traders", "ORD-42", null, new Amount(2550L, "BDT"), SessionStatus.PAID,
            NOW.plusSeconds(900), MaskingPolicy.mask(SensitiveType.MSISDN, "01711000001"),
            null, TXN_ID.toString(), redirect(paid, SessionStatus.PAID)));
    assertValid("OtpSent",
        new OtpSent(MaskingPolicy.mask(SensitiveType.MSISDN, "01711000001"), NOW.plusSeconds(120), 4));
    assertValid("PayResult", new PayResult(SessionStatus.PAYING, TXN_ID.toString(), null, null));
    assertValid("PayResult",
        new PayResult(SessionStatus.PAID, TXN_ID.toString(), null, redirect(paid, SessionStatus.PAID)));
    CheckoutSession failed = session(SessionStatus.FAILED, TXN_ID, "MW-CORE-INSUFFICIENT_BALANCE");
    assertValid("PayResult",
        new PayResult(SessionStatus.FAILED, TXN_ID.toString(),
            new ErrorInfo("MW-CORE-INSUFFICIENT_BALANCE", "Insufficient balance", "অপর্যাপ্ত ব্যালেন্স", false),
            redirect(failed, SessionStatus.FAILED)));
    CheckoutSession cancelled = session(SessionStatus.CANCELLED, null, null);
    assertValid("PayResult",
        new PayResult(SessionStatus.CANCELLED, null, null, redirect(cancelled, SessionStatus.CANCELLED)));
  }

  @Test
  void errorEnvelopesMatchTheContract() {
    assertValid("ErrorEnvelope", new ErrorEnvelope("MW-VAL-001", "Invalid request", null, null, CORRELATION_ID,
        false, List.of(new ErrorDetail("amount.valueMinor", "must be greater than 0"))));
    assertValid("ErrorEnvelope", new ErrorEnvelope("MW-CORE-INSUFFICIENT_BALANCE", "Insufficient balance",
        "অপর্যাপ্ত ব্যালেন্স", TXN_ID.toString(), CORRELATION_ID, false, null));
  }

  @Test
  void theIpnBodyMatchesTheWebhookContract() {
    IpnMapper mapper = new IpnMapper(JSON, Clock.fixed(NOW, ZoneOffset.UTC));
    Schema ipn = OpenApiSchemas.at(API, "webhooks", "ipn", "post", "requestBody", "content", "application/json",
        "schema");
    for (CheckoutSession s : List.of(
        session(SessionStatus.PAID, TXN_ID, null),
        session(SessionStatus.FAILED, TXN_ID, "MW-CORE-INSUFFICIENT_BALANCE"),
        session(SessionStatus.FAILED, null, "MW-TMO-001"))) {
      // the exact text IpnDeliveryService signs and posts
      JsonNode body = JSON.readTree(mapper.firstAttempt(s, NOW).payload());
      assertThat(ipn.validate(body)).as("%s", body).isEmpty();
      assertThat(OpenApiSchemas.component(API, "Ipn").validate(body)).isEmpty();
    }
  }

  @Test
  void refundViewsListAndTheRefundIpnMatchTheContract() {
    mfs.mw.checkout.mapper.RefundMapper mapper =
        new mfs.mw.checkout.mapper.RefundMapper(JSON, Clock.fixed(NOW, ZoneOffset.UTC));
    CheckoutSession paid = session(SessionStatus.PAID, TXN_ID, null);
    Schema ipn = OpenApiSchemas.at(API, "webhooks", "refundIpn", "post", "requestBody", "content",
        "application/json", "schema");
    List<mfs.mw.checkout.entity.Refund> refunds = List.of(
        refund(paid, mfs.mw.checkout.entity.Refund.Status.PENDING, null),
        refund(paid, mfs.mw.checkout.entity.Refund.Status.COMPLETED, null),
        refund(paid, mfs.mw.checkout.entity.Refund.Status.REJECTED, "MW-CORE-REFUND_EXCEEDS"));
    for (mfs.mw.checkout.entity.Refund r : refunds) {
      assertValid("Refund", mapper.view(r, paid));
      if (r.status() != mfs.mw.checkout.entity.Refund.Status.PENDING) {
        JsonNode body = JSON.readTree(mapper.firstIpnAttempt(r, paid, NOW).payload());
        assertThat(ipn.validate(body)).as("%s", body).isEmpty();
      }
    }
    assertValid("RefundList", new mfs.mw.checkout.dto.RefundList(
        refunds.stream().map(r -> mapper.view(r, paid)).toList(), new mfs.mw.checkout.dto.Amount(0L, "BDT")));
    // the REVERSAL the refund publishes is a valid mw.txn.request.v1 (owned by mw-orchestrator)
    JsonNode reversal = JSON.readTree(JSON.writeValueAsString(
        mapper.toTxnRequest(refunds.get(1), paid, CORRELATION_ID, NOW)));
    assertThat(ContractSchemas.schema("events/mw-orchestrator/mw.txn.request.v1.schema.json").validate(reversal))
        .as("%s", reversal).isEmpty();
  }

  private static mfs.mw.checkout.entity.Refund refund(
      CheckoutSession s, mfs.mw.checkout.entity.Refund.Status status, String errorCode) {
    return new mfs.mw.checkout.entity.Refund(mfs.mw.checkout.util.UuidV7.next(Clock.fixed(NOW, ZoneOffset.UTC)), s.sessionId(), s.merchantId(), "key-1", "h", 4_000,
        "BDT", s.txnId(), "returned", status, errorCode, NOW, NOW);
  }

  @Test
  void theOpenApiSchemasReallyValidate() {
    // guard against a $ref that resolves to nothing (which would accept anything)
    assertThat(OpenApiSchemas.component(API, "PayResult").validate(JSON.readTree("{\"status\":\"DONE\"}")))
        .isNotEmpty();
  }
}

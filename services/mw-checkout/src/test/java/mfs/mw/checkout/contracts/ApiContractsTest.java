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
        "https://shop.example.test/ipn", status, "01711000001", "hash", NOW.plusSeconds(120),
        0, 1, txnId, errorCode, NOW, NOW.plusSeconds(900), NOW.plusSeconds(30));
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
    assertThat(manifest.consumedApis()).isEmpty();
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
  }

  @Test
  void hostedPageResponsesMatchTheContract() {
    // CheckoutPayService.page / sendOtp / confirm / cancel
    CheckoutSession paid = session(SessionStatus.PAID, TXN_ID, null);
    assertValid("PageView",
        new PageView("Demo Shop", "ORD-42", "Order 42", new Amount(2550L, "BDT"), SessionStatus.OPEN,
            NOW.plusSeconds(900), null, new PageView.PinKey("core-pin-dev-1", "-----BEGIN PUBLIC KEY-----"),
            null, null));
    assertValid("PageView",
        new PageView("Demo Shop", "ORD-42", null, new Amount(2550L, "BDT"), SessionStatus.PAID,
            NOW.plusSeconds(900), MaskingPolicy.mask(SensitiveType.MSISDN, "01711000001"),
            new PageView.PinKey("core-pin-dev-1", null), TXN_ID.toString(), redirect(paid, SessionStatus.PAID)));
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
  void theOpenApiSchemasReallyValidate() {
    // guard against a $ref that resolves to nothing (which would accept anything)
    assertThat(OpenApiSchemas.component(API, "PayResult").validate(JSON.readTree("{\"status\":\"DONE\"}")))
        .isNotEmpty();
  }
}

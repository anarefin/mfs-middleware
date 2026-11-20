package mfs.mw.app.gateway.controller;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.regex.Pattern;
import mfs.mw.app.gateway.dto.BalanceView;
import mfs.mw.app.gateway.dto.ErrorDetail;
import mfs.mw.app.gateway.dto.SubmitResult;
import mfs.mw.app.gateway.dto.TransactionView;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.mapper.AppSoapMapper;
import mfs.mw.app.gateway.security.AppUserResolver;
import mfs.mw.app.gateway.service.BalanceService;
import mfs.mw.app.gateway.service.PreviewService;
import mfs.mw.app.gateway.service.TransactionService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;
import org.springframework.ws.soap.SoapHeaderElement;
import org.springframework.ws.soap.server.endpoint.annotation.SoapHeader;
import org.w3c.dom.Element;

/**
 * SOAP facade for the app (ticket 108, FRS CH-APP-01). Mapping and validation only: each operation
 * calls the same service as the REST controller of that operation. The bearer is the HTTP
 * {@code Authorization} header (the resource server on {@code /app/**}); {@code Idempotency-Key}
 * is a SOAP header. {@code X-Correlation-Id} is the HTTP header {@code CorrelationIdFilter} already
 * reads for every request, including this servlet. Payloads are not logged.
 */
@Endpoint
public class AppSoapEndpoint {

  private static final Pattern KEY = Pattern.compile("[\\x21-\\x7E]{1,64}");

  private final PreviewService previews;
  private final BalanceService balances;
  private final TransactionService transactions;
  private final AppUserResolver users;
  private final AppSoapMapper mapper;
  private final Validator validator;

  public AppSoapEndpoint(
      PreviewService previews, BalanceService balances, TransactionService transactions, AppUserResolver users,
      AppSoapMapper mapper, Validator validator) {
    this.previews = previews;
    this.balances = balances;
    this.transactions = transactions;
    this.users = users;
    this.mapper = mapper;
    this.validator = validator;
  }

  @PayloadRoot(namespace = AppSoap.NAMESPACE, localPart = "PreviewRequest")
  @ResponsePayload
  public Element preview(@RequestPayload Element body) {
    var request = mapper.previewRequest(body);
    validate(request);
    return mapper.preview(previews.preview(user(), mapper.group(body), request));
  }

  @PayloadRoot(namespace = AppSoap.NAMESPACE, localPart = "ConfirmRequest")
  @ResponsePayload
  public Element confirm(
      @RequestPayload Element body,
      @SoapHeader(AppSoap.IDEMPOTENCY_HEADER) SoapHeaderElement key) {
    var request = mapper.confirmRequest(body);
    validate(request);
    SubmitResult result = previews.confirm(user(), mapper.group(body), idempotencyKey(key), request);
    return mapper.transaction("ConfirmResponse", result.view());
  }

  @PayloadRoot(namespace = AppSoap.NAMESPACE, localPart = "GetBalanceRequest")
  @ResponsePayload
  public Element balance(
      @RequestPayload Element body,
      @SoapHeader(AppSoap.IDEMPOTENCY_HEADER) SoapHeaderElement key) {
    var request = mapper.balanceRequest(body);
    validate(request);
    BalanceView view = balances.check(user(), idempotencyKey(key), request);
    return mapper.balance(view);
  }

  @PayloadRoot(namespace = AppSoap.NAMESPACE, localPart = "GetTransactionStatusRequest")
  @ResponsePayload
  public Element status(@RequestPayload Element body) {
    String txnId = mapper.txnId(body);
    if (txnId == null) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Request validation failed")
          .withDetails(List.of(new ErrorDetail("txnId", "is required")));
    }
    TransactionView view = transactions.find(user(), txnId);
    return mapper.transaction("GetTransactionStatusResponse", view);
  }

  private void validate(Object bean) {
    if (bean == null) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Request validation failed");
    }
    var violations = validator.validate(bean);
    if (violations.isEmpty()) {
      return;
    }
    throw new MwException(ErrorCodes.VAL_INVALID, "Request validation failed")
        .withDetails(violations.stream().map(AppSoapEndpoint::detail).toList());
  }

  private static ErrorDetail detail(ConstraintViolation<?> violation) {
    String field = violation.getPropertyPath().toString();
    return new ErrorDetail(field.isEmpty() ? "body" : field, violation.getMessage());
  }

  /** The same rule as the REST {@code Idempotency-Key} header. */
  private static String idempotencyKey(SoapHeaderElement header) {
    String key;
    try {
      key = header == null ? null : header.getText();
    } catch (RuntimeException e) {
      key = null;
    }
    if (key == null || key.isBlank()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Request validation failed")
          .withDetails(List.of(new ErrorDetail(AppSoap.IDEMPOTENCY_KEY, "is required")));
    }
    key = key.strip();
    if (!KEY.matcher(key).matches()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Request validation failed")
          .withDetails(List.of(new ErrorDetail(
              AppSoap.IDEMPOTENCY_KEY, "must be 1 to 64 visible ASCII characters")));
    }
    return key;
  }

  private mfs.mw.app.gateway.dto.AppUser user() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
      return users.resolve(jwt);
    }
    throw new MwException(ErrorCodes.AUTH_UNAUTHENTICATED, "Authentication required");
  }
}

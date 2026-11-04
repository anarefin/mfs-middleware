package mfs.mw.portal.api.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import mfs.mw.portal.api.dto.ErrorDetail;
import mfs.mw.portal.api.dto.ErrorEnvelope;
import mfs.mw.portal.api.filter.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every exception into the error envelope (spec 4.4 rule 4, 5.3). No stack trace or
 * internal message ever reaches the client (18.6); they go to the log only.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  static final String UNMATCHED_ROUTE = "<unmatched>";

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(MwException.class)
  ResponseEntity<ErrorEnvelope> mw(MwException e, HttpServletRequest req) {
    return respond(e, req, e);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ErrorEnvelope> invalidBody(
      MethodArgumentNotValidException e, HttpServletRequest req) {
    List<ErrorDetail> details =
        e.getBindingResult().getFieldErrors().stream().map(GlobalExceptionHandler::detail).toList();
    return validation(ErrorCodes.VAL_INVALID, "Request validation failed", details, req, e);
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  ResponseEntity<ErrorEnvelope> invalidParams(
      HandlerMethodValidationException e, HttpServletRequest req) {
    List<ErrorDetail> details =
        e.getParameterValidationResults().stream()
            .flatMap(
                r ->
                    r.getResolvableErrors().stream()
                        .map(
                            err ->
                                new ErrorDetail(
                                    r.getMethodParameter().getParameterName(),
                                    err.getDefaultMessage())))
            .toList();
    return validation(ErrorCodes.VAL_INVALID, "Request validation failed", details, req, e);
  }

  @ExceptionHandler({
    MissingServletRequestParameterException.class,
    MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<ErrorEnvelope> badParam(Exception e, HttpServletRequest req) {
    return validation(ErrorCodes.VAL_INVALID, "Request validation failed", List.of(), req, e);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ErrorEnvelope> malformed(Exception e, HttpServletRequest req) {
    return validation(ErrorCodes.VAL_MALFORMED, "Malformed request body", List.of(), req, e);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  ResponseEntity<ErrorEnvelope> notFound(Exception e, HttpServletRequest req) {
    return respond(
        new MwException(ErrorCodes.VAL_NOT_FOUND, "Not found").withStatus(HttpStatus.NOT_FOUND),
        req, e);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ErrorEnvelope> method(Exception e, HttpServletRequest req) {
    return respond(
        new MwException(ErrorCodes.VAL_METHOD, "Method not allowed")
            .withStatus(HttpStatus.METHOD_NOT_ALLOWED),
        req, e);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ErrorEnvelope> mediaType(Exception e, HttpServletRequest req) {
    return respond(
        new MwException(ErrorCodes.VAL_MEDIA_TYPE, "Unsupported media type")
            .withStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
        req, e);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorEnvelope> unexpected(Exception e, HttpServletRequest req) {
    return respond(new MwException(ErrorCodes.SYS_INTERNAL, "Internal error"), req, e);
  }

  private ResponseEntity<ErrorEnvelope> validation(
      String code, String message, List<ErrorDetail> details, HttpServletRequest req,
      Exception e) {
    return respond(new MwException(code, message).withDetails(details), req, e);
  }

  /**
   * Logs and answers {@code error}: its status, and its code, messages, txnId, retryable flag and
   * details as the envelope. {@code cause} is the exception actually caught (logged on 5xx only);
   * for an {@link MwException} it is {@code error} itself.
   */
  private ResponseEntity<ErrorEnvelope> respond(
      MwException error, HttpServletRequest req, Exception cause) {
    HttpStatus status = error.status();
    String code = error.code();
    if (status.is5xxServerError()) {
      log.atError()
          .addKeyValue("event", "http.request.failed")
          .addKeyValue("errorCode", code)
          .setCause(cause)
          .log("{} {} failed with {}", req.getMethod(), route(req), status.value());
    } else {
      log.atWarn()
          .addKeyValue("event", "http.request.rejected")
          .addKeyValue("errorCode", code)
          .log("{} {} rejected with {}", req.getMethod(), route(req), status.value());
    }
    ErrorEnvelope body =
        new ErrorEnvelope(
            code, error.getMessage(), error.messageBn(), error.txnId(), correlationId(req),
            error.retryable(), error.details());
    return ResponseEntity.status(status).body(body);
  }

  /**
   * The matched route template (e.g. {@code /accounts/{accountNo}}), never the raw URI or query
   * string, which can carry MSISDNs and account numbers (spec 18.3).
   */
  static String route(HttpServletRequest req) {
    Object pattern = req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    return pattern != null ? pattern.toString() : UNMATCHED_ROUTE;
  }

  private static String correlationId(HttpServletRequest req) {
    Object attr = req.getAttribute(CorrelationIdFilter.MDC_KEY);
    return attr != null ? attr.toString() : MDC.get(CorrelationIdFilter.MDC_KEY);
  }

  private static ErrorDetail detail(FieldError f) {
    return new ErrorDetail(f.getField(), f.getDefaultMessage());
  }
}

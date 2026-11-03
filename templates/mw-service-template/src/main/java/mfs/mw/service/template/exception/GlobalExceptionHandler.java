package mfs.mw.service.template.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import mfs.mw.service.template.dto.ErrorDetail;
import mfs.mw.service.template.dto.ErrorEnvelope;
import mfs.mw.service.template.filter.CorrelationIdFilter;
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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every exception into the error envelope (spec 4.4 rule 4, 5.3). No stack trace or
 * internal message ever reaches the client (18.6); they go to the log only.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(MwException.class)
  ResponseEntity<ErrorEnvelope> mw(MwException e, HttpServletRequest req) {
    return respond(
        e.status(), e.code(), e.getMessage(), e.messageBn(), e.txnId(), e.retryable(),
        e.details(), req, e);
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
        HttpStatus.NOT_FOUND, ErrorCodes.VAL_NOT_FOUND, "Not found", null, null, false,
        List.of(), req, e);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ErrorEnvelope> method(Exception e, HttpServletRequest req) {
    return respond(
        HttpStatus.METHOD_NOT_ALLOWED, ErrorCodes.VAL_METHOD, "Method not allowed", null, null,
        false, List.of(), req, e);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ErrorEnvelope> mediaType(Exception e, HttpServletRequest req) {
    return respond(
        HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCodes.VAL_MEDIA_TYPE, "Unsupported media type",
        null, null, false, List.of(), req, e);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorEnvelope> unexpected(Exception e, HttpServletRequest req) {
    return respond(
        HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.SYS_INTERNAL, "Internal error", null, null,
        ErrorCategory.SYS.retryable(), List.of(), req, e);
  }

  private ResponseEntity<ErrorEnvelope> validation(
      String code, String message, List<ErrorDetail> details, HttpServletRequest req,
      Exception e) {
    return respond(HttpStatus.BAD_REQUEST, code, message, null, null, false, details, req, e);
  }

  private ResponseEntity<ErrorEnvelope> respond(
      HttpStatus status, String code, String message, String messageBn, String txnId,
      boolean retryable, List<ErrorDetail> details, HttpServletRequest req, Exception e) {
    if (status.is5xxServerError()) {
      log.error("event=http.request.failed errorCode={} status={}", code, status.value(), e);
    } else {
      log.warn("event=http.request.rejected errorCode={} status={}", code, status.value());
    }
    ErrorEnvelope body =
        new ErrorEnvelope(
            code, message, messageBn, txnId, correlationId(req), retryable, details);
    return ResponseEntity.status(status).body(body);
  }

  private static String correlationId(HttpServletRequest req) {
    Object attr = req.getAttribute(CorrelationIdFilter.MDC_KEY);
    return attr != null ? attr.toString() : MDC.get(CorrelationIdFilter.MDC_KEY);
  }

  private static ErrorDetail detail(FieldError f) {
    return new ErrorDetail(f.getField(), f.getDefaultMessage());
  }
}

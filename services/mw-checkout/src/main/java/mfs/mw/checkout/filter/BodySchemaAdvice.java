package mfs.mw.checkout.filter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.Map;
import mfs.mw.checkout.dto.ConfirmRequest;
import mfs.mw.checkout.dto.CreateSessionRequest;
import mfs.mw.checkout.dto.OtpRequest;
import mfs.mw.checkout.exception.ErrorCodes;
import mfs.mw.checkout.exception.MwException;
import mfs.mw.checkout.service.BodySchema;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks every public JSON request body against its {@link BodySchema} before Jackson binds it:
 * {@link CreateSessionRequest} (merchant API), {@link OtpRequest} and {@link ConfirmRequest}
 * (hosted page API). The bytes are read once (at most {@link #MAX_BODY} bytes, else 413
 * MW-VAL-BODY_TOO_LARGE), checked, and the same bytes are handed to the converter, so what is
 * validated is exactly what is bound. A failure is an {@code MwException}, answered by the global
 * handler. A body type not listed here is not checked: add its schema when adding one.
 */
@ControllerAdvice
public class BodySchemaAdvice extends RequestBodyAdviceAdapter {

  /** Above the edge's caps (16 KiB {@code checkout-v1-sessions}, 8 KiB {@code checkout-pay}). */
  static final int MAX_BODY = 64 * 1024;

  private final Map<Type, BodySchema> schemas;

  public BodySchemaAdvice(JsonMapper json) {
    this.schemas =
        Map.of(
            CreateSessionRequest.class, new BodySchema(json, BodySchema.CREATE_SESSION),
            OtpRequest.class, new BodySchema(json, BodySchema.PAY_OTP),
            ConfirmRequest.class, new BodySchema(json, BodySchema.PAY_CONFIRM));
  }

  @Override
  public boolean supports(
      MethodParameter parameter, Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
    return schemas.containsKey(targetType);
  }

  @Override
  public HttpInputMessage beforeBodyRead(
      HttpInputMessage input, MethodParameter parameter, Type targetType,
      Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
    byte[] body = input.getBody().readNBytes(MAX_BODY + 1);
    if (body.length > MAX_BODY) {
      throw new MwException(ErrorCodes.VAL_BODY_TOO_LARGE, "Request body too large")
          .withStatus(HttpStatus.CONTENT_TOO_LARGE);
    }
    schemas.get(targetType).check(body);
    HttpHeaders headers = input.getHeaders();
    return new HttpInputMessage() {
      @Override
      public InputStream getBody() {
        return new ByteArrayInputStream(body);
      }

      @Override
      public HttpHeaders getHeaders() {
        return headers;
      }
    };
  }
}

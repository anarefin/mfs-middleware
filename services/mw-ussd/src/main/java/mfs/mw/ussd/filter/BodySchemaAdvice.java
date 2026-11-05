package mfs.mw.ussd.filter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.Map;
import mfs.mw.ussd.dto.HttpUssdRequest;
import mfs.mw.ussd.exception.ErrorCodes;
import mfs.mw.ussd.exception.MwException;
import mfs.mw.ussd.service.BodySchema;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks the HTTP USSD step body ({@link HttpUssdRequest}) against its {@link BodySchema} before
 * Jackson binds it (copied from mw-checkout): the bytes are read once (at most {@link #MAX_BODY}
 * bytes, else 413 MW-VAL-BODY_TOO_LARGE), checked, and the same bytes are handed to the converter,
 * so what is validated is exactly what is bound. A failure is an {@code MwException}, answered by
 * the global handler. A new JSON body type needs its derived schema added here.
 */
@ControllerAdvice
public class BodySchemaAdvice extends RequestBodyAdviceAdapter {

  /** Well above the edge's 4 KiB cap on {@code ussd-http-<mno>}: a direct caller gets no more. */
  static final int MAX_BODY = 64 * 1024;

  private final Map<Type, BodySchema> schemas;

  public BodySchemaAdvice(JsonMapper json) {
    this.schemas = Map.of(HttpUssdRequest.class, new BodySchema(json, BodySchema.HTTP_STEP));
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

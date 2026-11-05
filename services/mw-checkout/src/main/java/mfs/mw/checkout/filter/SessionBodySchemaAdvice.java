package mfs.mw.checkout.filter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import mfs.mw.checkout.dto.CreateSessionRequest;
import mfs.mw.checkout.exception.ErrorCodes;
import mfs.mw.checkout.exception.MwException;
import mfs.mw.checkout.service.SessionBodySchema;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks every {@link CreateSessionRequest} body against {@link SessionBodySchema} before Jackson
 * binds it: the bytes are read once (at most {@link #MAX_BODY} bytes, else 413
 * MW-VAL-BODY_TOO_LARGE), checked, and the same bytes are handed to the converter, so what is
 * validated is exactly what is bound. A failure is an {@code MwException}, answered by the
 * global handler.
 */
@ControllerAdvice
public class SessionBodySchemaAdvice extends RequestBodyAdviceAdapter {

  /** Above the edge's 16 KiB cap on {@code checkout-v1-sessions}: a direct caller gets no more. */
  static final int MAX_BODY = 64 * 1024;

  private final SessionBodySchema schema;

  public SessionBodySchemaAdvice(JsonMapper json) {
    this.schema = new SessionBodySchema(json);
  }

  @Override
  public boolean supports(
      MethodParameter parameter, Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
    return targetType == CreateSessionRequest.class;
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
    schema.check(body);
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

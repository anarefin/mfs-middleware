package mfs.mw.app.gateway.filter;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import org.springframework.http.HttpStatus;

/**
 * A request whose body was read once into memory, so the signature check can hash the exact bytes
 * and the controller can still bind them. At most {@link #MAX_BODY} bytes are kept, as APISIX
 * caps the route: the gateway does not rely on the edge for that and never reads more than one byte
 * past the cap ({@code readNBytes(limit + 1)}), so a larger body is 413 MW-VAL-BODY_TOO_LARGE
 * without being buffered.
 */
public class CachedBodyRequest extends HttpServletRequestWrapper {

  /** 64 KiB, the edge's {@code client-control max_body_size} on {@code /app/v1/*}. */
  public static final int MAX_BODY = 64 * 1024;

  private final byte[] body;

  /** Reads the body; a body over {@code limit} bytes is an {@link MwException} answered 413. */
  public CachedBodyRequest(HttpServletRequest request, int limit) throws IOException {
    super(request);
    byte[] read = request.getInputStream().readNBytes(limit + 1);
    if (read.length > limit) {
      throw new MwException(ErrorCodes.VAL_BODY_TOO_LARGE, "Request body too large")
          .withStatus(HttpStatus.CONTENT_TOO_LARGE);
    }
    this.body = read;
  }

  public byte[] body() {
    return body.clone();
  }

  @Override
  public ServletInputStream getInputStream() {
    ByteArrayInputStream in = new ByteArrayInputStream(body);
    return new ServletInputStream() {
      @Override
      public int read() {
        return in.read();
      }

      @Override
      public int read(byte[] b, int off, int len) {
        return in.read(b, off, len);
      }

      @Override
      public boolean isFinished() {
        return in.available() == 0;
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setReadListener(ReadListener listener) {
        throw new UnsupportedOperationException("synchronous body only");
      }
    };
  }

  @Override
  public BufferedReader getReader() {
    String enc = getCharacterEncoding();
    Charset cs = enc == null ? StandardCharsets.UTF_8 : Charset.forName(enc);
    return new BufferedReader(new InputStreamReader(getInputStream(), cs));
  }
}

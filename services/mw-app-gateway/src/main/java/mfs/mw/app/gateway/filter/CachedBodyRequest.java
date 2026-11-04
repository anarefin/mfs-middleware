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

/**
 * A request whose body was read once into memory, so the signature check can hash the exact bytes
 * and the controller can still bind them. Bodies are small: APISIX caps them at 64 KiB.
 */
public class CachedBodyRequest extends HttpServletRequestWrapper {

  private final byte[] body;

  public CachedBodyRequest(HttpServletRequest request) throws IOException {
    super(request);
    this.body = request.getInputStream().readAllBytes();
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

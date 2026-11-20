package mfs.mw.app.gateway.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Spring-WS answers a SOAP 1.1 client fault ({@code soapenv:Client}) with HTTP 500. Ticket 108
 * says that fault is HTTP 400. The body is kept: it is the SOAP fault, and it is not logged
 * (it can carry an MSISDN when the fault is not a validation rejection).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SoapClientFaultStatusFilter extends OncePerRequestFilter {

  static final String SOAP_PATH = "/app/ws/v1";

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String uri = request.getRequestURI();
    return uri == null || !(uri.equals(SOAP_PATH) || uri.startsWith(SOAP_PATH + "/"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    SoapStatusResponse buffered = new SoapStatusResponse(response);
    chain.doFilter(request, buffered);
    buffered.copyTo(response);
  }

  /** A SOAP 1.1 {@code Client} fault or a SOAP 1.2 {@code Sender} fault. */
  static boolean clientFault(byte[] body) {
    String xml = new String(body, StandardCharsets.UTF_8);
    return xml.contains(":Client<") || xml.contains(">Client<") || xml.contains(":Sender<");
  }

  static final class SoapStatusResponse extends HttpServletResponseWrapper {

    private int status = HttpStatus.OK.value();
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private ServletOutputStream stream;
    private PrintWriter writer;

    SoapStatusResponse(HttpServletResponse response) {
      super(response);
    }

    @Override
    public void setStatus(int sc) {
      status = sc;
    }

    @Override
    public int getStatus() {
      return status;
    }

    @Override
    public void sendError(int sc) {
      status = sc;
    }

    @Override
    public void sendError(int sc, String msg) {
      status = sc;
    }

    @Override
    public void flushBuffer() {}

    @Override
    public void resetBuffer() {
      buffer.reset();
    }

    @Override
    public ServletOutputStream getOutputStream() {
      if (writer != null) {
        throw new IllegalStateException("getWriter() already called");
      }
      if (stream == null) {
        stream = new ServletOutputStream() {
          @Override
          public boolean isReady() {
            return true;
          }

          @Override
          public void setWriteListener(WriteListener listener) {}

          @Override
          public void write(int b) {
            buffer.write(b);
          }
        };
      }
      return stream;
    }

    @Override
    public PrintWriter getWriter() {
      if (stream != null) {
        throw new IllegalStateException("getOutputStream() already called");
      }
      if (writer == null) {
        writer = new PrintWriter(new OutputStreamWriter(buffer, StandardCharsets.UTF_8), false);
      }
      return writer;
    }

    void copyTo(HttpServletResponse real) throws IOException {
      if (writer != null) {
        writer.flush();
      }
      byte[] bytes = buffer.toByteArray();
      int sc = status == HttpStatus.INTERNAL_SERVER_ERROR.value() && clientFault(bytes)
          ? HttpStatus.BAD_REQUEST.value()
          : status;
      real.setStatus(sc);
      if (bytes.length > 0) {
        real.getOutputStream().write(bytes);
      }
    }
  }
}

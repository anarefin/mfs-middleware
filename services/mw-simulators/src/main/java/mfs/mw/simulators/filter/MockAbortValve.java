package mfs.mw.simulators.filter;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.coyote.ActionCode;

/**
 * Tomcat valve for mock control (ticket 102): a request marked with {@link #ABORT_ATTRIBUTE} (switch
 * DOWN, scenario CONNECTION_RESET) gets no answer at all: the buffered response is dropped and the
 * socket closed, so the caller sees the connection end before any status line.
 */
public class MockAbortValve extends ValveBase {

  public static final String ABORT_ATTRIBUTE = MockAbortValve.class.getName() + ".party";

  public MockAbortValve() {
    super(true);
  }

  @Override
  public void invoke(Request request, Response response) throws IOException, ServletException {
    getNext().invoke(request, response);
    if (request.getAttribute(ABORT_ATTRIBUTE) != null) {
      if (!response.isCommitted()) {
        response.resetBuffer();
      }
      response.getCoyoteResponse().action(ActionCode.CLOSE_NOW, null);
    }
  }
}

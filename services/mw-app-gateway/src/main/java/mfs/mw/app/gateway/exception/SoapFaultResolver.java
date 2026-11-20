package mfs.mw.app.gateway.exception;

import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.server.EndpointExceptionResolver;
import org.springframework.ws.soap.SoapMessage;

/**
 * Every failure of the SOAP facade becomes a SOAP fault with the error envelope in {@code detail}
 * (ticket 108). An unexpected exception is {@code MW-SYS-001}; its own message stays in the log.
 */
@Component
public class SoapFaultResolver implements EndpointExceptionResolver, Ordered {

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  @Override
  public boolean resolveException(MessageContext messageContext, Object endpoint, Exception ex) {
    if (!(messageContext.getResponse() instanceof SoapMessage response)) {
      return false;
    }
    MwException error = unwrap(ex);
    if (error == null) {
      error = new MwException(ErrorCodes.SYS_INTERNAL, "Internal error");
    }
    if (response.getSoapBody().hasFault()) {
      return true;
    }
    SoapFaults.write(response, error);
    return true;
  }

  private static MwException unwrap(Throwable ex) {
    Throwable current = ex;
    while (current != null) {
      if (current instanceof MwException mw) {
        return mw;
      }
      current = current.getCause();
    }
    return null;
  }
}

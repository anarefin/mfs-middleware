package mfs.mw.ussd.transport;

import mfs.mw.ussd.config.UssdProperties;
import mfs.mw.ussd.dto.HttpUssdRequest;
import mfs.mw.ussd.dto.HttpUssdResponse;
import mfs.mw.ussd.dto.UssdEvent;
import mfs.mw.ussd.dto.UssdRequest;
import mfs.mw.ussd.dto.UssdResponse;
import mfs.mw.ussd.exception.ErrorCodes;
import mfs.mw.ussd.exception.MwException;
import mfs.mw.ussd.service.UssdEngine;
import mfs.mw.ussd.util.Msisdn;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * {@code HttpUssdTransport} (spec 9.2): the MNO POSTs each step to {@code /ussd/{mno}} and the
 * response body is the next screen plus the MNO's continue/end flag. Only MNOs configured with
 * transport HTTP are served here; the MNO-provided session id is the session id.
 */
@Component
public class HttpUssdTransport implements UssdTransport {

  private final UssdEngine engine;
  private final UssdProperties props;

  public HttpUssdTransport(UssdEngine engine, UssdProperties props) {
    this.engine = engine;
    this.props = props;
  }

  @Override
  public UssdProperties.Transport type() {
    return UssdProperties.Transport.HTTP;
  }

  /** One MNO step: refuses MNOs not on HTTP (404) and numbers that are not Bangladeshi (400). */
  public HttpUssdResponse handle(String mno, HttpUssdRequest wire) {
    UssdProperties.Mno config = props.mno().get(mno);
    if (config == null || config.transport() != UssdProperties.Transport.HTTP) {
      throw new MwException(ErrorCodes.VAL_NOT_FOUND, "Not found").withStatus(HttpStatus.NOT_FOUND);
    }
    String msisdn = Msisdn.canonical(wire.msisdn());
    if (msisdn == null) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Request validation failed")
          .withDetails(java.util.List.of(new mfs.mw.ussd.dto.ErrorDetail("msisdn", "is not a mobile number")));
    }
    UssdRequest req = new UssdRequest(mno, wire.sessionId(), msisdn, wire.type(), wire.input());
    UssdResponse r =
        switch (wire.type()) {
          case BEGIN -> onBegin(req);
          case CONTINUE -> onContinue(req);
          case END, ABORT -> {
            if (wire.type() == UssdEvent.END) {
              onEnd(req);
            } else {
              onAbort(req);
            }
            yield new UssdResponse(wire.sessionId(), "", true);
          }
        };
    return new HttpUssdResponse(r.sessionId(), r.text(), r.end() ? config.endFlag() : config.continueFlag());
  }

  @Override
  public UssdResponse onBegin(UssdRequest request) {
    return engine.handle(request);
  }

  @Override
  public UssdResponse onContinue(UssdRequest request) {
    return engine.handle(request);
  }

  @Override
  public void onEnd(UssdRequest request) {
    engine.handle(request);
  }

  @Override
  public void onAbort(UssdRequest request) {
    engine.handle(request);
  }

  /** HTTP answers in the response body; a network-initiated push needs SMPP. */
  @Override
  public void send(UssdResponse response) {
    throw new UnsupportedOperationException("HTTP USSD answers in the response body only");
  }
}

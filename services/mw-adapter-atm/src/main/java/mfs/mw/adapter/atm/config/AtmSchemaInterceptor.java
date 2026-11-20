package mfs.mw.adapter.atm.config;

import java.util.List;
import javax.xml.transform.TransformerException;
import mfs.mw.adapter.atm.dto.ErrorDetail;
import mfs.mw.adapter.atm.exception.ErrorCodes;
import mfs.mw.adapter.atm.exception.MwException;
import mfs.mw.adapter.atm.exception.SoapFaultResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.server.endpoint.interceptor.PayloadValidatingInterceptor;
import org.xml.sax.SAXParseException;

/**
 * XSD validation of an inbound ATM SOAP request (ticket 120a, FRS PLT-006, PLT-050). A document
 * that is not the schema is a client fault {@code MW-VAL-001}. The parser message is not logged
 * and not returned: it can repeat the MSISDN or the one-time token (FL-11).
 */
public class AtmSchemaInterceptor extends PayloadValidatingInterceptor {

  private static final Logger log = LoggerFactory.getLogger(AtmSchemaInterceptor.class);

  @Override
  protected boolean handleRequestValidationErrors(MessageContext messageContext, SAXParseException[] errors)
      throws TransformerException {
    log.atWarn()
        .addKeyValue("event", "atm.inbound.validation.rejected")
        .addKeyValue("errorCode", ErrorCodes.VAL_INVALID)
        .log("ATM SOAP request failed XSD validation");
    if (messageContext.getResponse() instanceof SoapMessage response) {
      SoapFaultResolver.write(response, new MwException(ErrorCodes.VAL_INVALID, "Request validation failed")
          .withDetails(List.of(new ErrorDetail("body", "does not match the schema"))));
    }
    return false;
  }
}

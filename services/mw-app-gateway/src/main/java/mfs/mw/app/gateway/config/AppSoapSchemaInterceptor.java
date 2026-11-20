package mfs.mw.app.gateway.config;

import java.util.List;
import javax.xml.transform.TransformerException;
import mfs.mw.app.gateway.dto.ErrorDetail;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.exception.SoapFaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.server.endpoint.interceptor.PayloadValidatingInterceptor;
import org.xml.sax.SAXParseException;

/**
 * XSD validation of a SOAP request (ticket 108, FRS PLT-006). A document that is not the schema
 * is a client fault {@code MW-VAL-001}. The parser message is not logged and not returned: it can
 * repeat a value from the body (an MSISDN). Payload logging stays off; this interceptor does not
 * log the document.
 */
public class AppSoapSchemaInterceptor extends PayloadValidatingInterceptor {

  private static final Logger log = LoggerFactory.getLogger(AppSoapSchemaInterceptor.class);

  @Override
  protected boolean handleRequestValidationErrors(MessageContext messageContext, SAXParseException[] errors)
      throws TransformerException {
    log.atWarn()
        .addKeyValue("event", "soap.validation.rejected")
        .addKeyValue("errorCode", ErrorCodes.VAL_INVALID)
        .log("SOAP request failed XSD validation");
    if (messageContext.getResponse() instanceof SoapMessage response) {
      SoapFaults.write(response, new MwException(ErrorCodes.VAL_INVALID, "Request validation failed")
          .withDetails(List.of(new ErrorDetail("body", "does not match the schema"))));
    }
    return false;
  }
}

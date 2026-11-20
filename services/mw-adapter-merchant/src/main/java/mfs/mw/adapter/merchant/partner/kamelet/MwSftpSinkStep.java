package mfs.mw.adapter.merchant.partner.kamelet;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import mfs.mw.adapter.merchant.partner.route.PartnerExchange;
import mfs.mw.adapter.merchant.partner.route.PartnerRuntime;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The steps of the {@code mw-sftp-sink} Kamelet (bean {@code mwSftpSink}, review of ticket 86).
 * With {@code mapping}, the file is the text a JSONata mapping of the record makes of the JSON body:
 * CSV or fixed-width lines ({@code $join}, {@code $pad}, {@code $formatNumber}); the mapping must
 * give a string of at most {@value #MAX_TEXT} characters. JSONata rather than {@code marshal: bindy}:
 * bindy needs a Java model class per layout, which a route cannot ship and the loader's allowlist
 * cannot check. In a request route the put is the partner call: it counts as sent once the upload
 * starts (a failed put is then UNKNOWN, as for any call that may have arrived), and after it {@code
 * mw-classify} sees status 200 and the body {@code {"fileName": <name>}}. The route's body is the one
 * before the sink. Nothing of the body or the file is logged or put in an error message.
 */
@Component("mwSftpSink")
public class MwSftpSinkStep {

  /** The largest file text a mapping may make. */
  static final int MAX_TEXT = 16 * 1024 * 1024;
  /** The body before the sink, put back after the file is written. */
  static final String KEPT_BODY = "mwSftpSinkBody";

  private final JsonMapper json;

  public MwSftpSinkStep(JsonMapper json) {
    this.json = json;
  }

  /** Before the put: the file text from the mapping (none: the body as it is). */
  public void content(Exchange exchange, String mapping) {
    var message = exchange.getMessage();
    exchange.setProperty(KEPT_BODY, message.getBody());
    PartnerRuntime runtime = exchange.getProperty(PartnerExchange.RUNTIME, PartnerRuntime.class);
    if (mapping == null || mapping.isEmpty()) {
      if (runtime != null) {
        exchange.setProperty(PartnerExchange.SENT, true);
      }
      return;
    }
    if (runtime == null) {
      throw new IllegalArgumentException("mw-sftp-sink mapping needs a request route (a JSON body)");
    }
    Object input;
    try {
      input = json.readValue(message.getBody(String.class), Object.class);
    } catch (JacksonException | IllegalArgumentException e) {
      throw new IllegalArgumentException("mw-sftp-sink mapping " + mapping + ": the body is not JSON");
    }
    Object text = runtime.mapping(mapping).evaluate(input);
    if (!(text instanceof String s)) {
      throw new IllegalArgumentException("mw-sftp-sink mapping " + mapping + " must make text (a string)");
    }
    if (s.length() > MAX_TEXT) {
      throw new IllegalArgumentException("mw-sftp-sink mapping " + mapping + " made more than " + MAX_TEXT + " characters");
    }
    message.setBody(s.getBytes(StandardCharsets.UTF_8));
    exchange.setProperty(PartnerExchange.SENT, true);
  }

  /** After the put: the body before the sink; in a request route the answer mw-classify maps. */
  public void put(Exchange exchange) {
    var message = exchange.getMessage();
    message.setBody(exchange.getProperty(KEPT_BODY));
    exchange.removeProperty(KEPT_BODY);
    if (exchange.getProperty(PartnerExchange.RUNTIME) == null) {
      return;
    }
    String produced = message.getHeader(Exchange.FILE_NAME_PRODUCED, String.class);
    String name = produced == null ? "" : produced.substring(produced.lastIndexOf('/') + 1);
    exchange.setProperty(PartnerExchange.RESPONSE_STATUS, 200);
    exchange.setProperty(PartnerExchange.RESPONSE_BODY, json.writeValueAsString(Map.of("fileName", name)));
  }
}

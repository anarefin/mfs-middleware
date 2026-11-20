package mfs.mw.adapter.card.partner.kamelet;

import java.util.LinkedHashMap;
import java.util.Map;
import mfs.mw.adapter.card.partner.route.PartnerExchange;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The step of the {@code mw-map} Kamelet (bean {@code mwMap}): the record's JSONata mapping named
 * by the Kamelet's {@code mapping} turns the canonical call into the partner's request body. Input:
 * the call's canonical document plus {@code reference}, {@code operation} and {@code txnId}, as
 * the former adapter gave it. A mapping that fails throws, and the partner route configuration
 * answers NOT_SENT {@code MISCONFIGURED} (nothing was sent).
 */
@Component("mwMap")
public class MwMapStep {

  private final JsonMapper json;

  public MwMapStep(JsonMapper json) {
    this.json = json;
  }

  @SuppressWarnings("unchecked")
  public void request(Exchange exchange) {
    String name = exchange.getProperty(PartnerExchange.MAPPING, String.class);
    Map<String, Object> call = json.readValue(exchange.getMessage().getBody(String.class), Map.class);
    Map<String, Object> input = new LinkedHashMap<>();
    if (call.get("input") instanceof Map<?, ?> in) {
      input.putAll((Map<String, Object>) in);
    }
    input.put("reference", call.get("reference"));
    input.put("operation", call.get("operation"));
    input.put("txnId", call.get("txnId"));
    Object mapped = PartnerExchange.runtime(exchange).mapping(name).evaluate(input);
    String body = json.writeValueAsString(mapped);
    exchange.setProperty(PartnerExchange.REQUEST_BODY, body);
    exchange.getMessage().setBody(body);
  }
}

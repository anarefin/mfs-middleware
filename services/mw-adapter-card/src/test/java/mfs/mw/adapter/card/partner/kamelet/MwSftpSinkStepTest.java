package mfs.mw.adapter.card.partner.kamelet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mfs.mw.adapter.card.partner.PartnerTestSeeds;
import mfs.mw.adapter.card.partner.mapping.CompiledMapping;
import mfs.mw.adapter.card.partner.route.PartnerExchange;
import mfs.mw.adapter.card.partner.route.PartnerRuntime;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code mw-sftp-sink} steps (review of ticket 86): with {@code mapping} the file is the text a
 * JSONata mapping of the record makes of the JSON body (CSV or fixed-width lines), and in a request
 * route the put is the partner call: {@code mw-classify} then sees status 200 and the file name,
 * and the route's body is what it was before the sink.
 */
class MwSftpSinkStepTest {

  static final JsonMapper JSON = JsonMapper.builder().build();
  final MwSftpSinkStep step = new MwSftpSinkStep(JSON);

  static Exchange call(Map<String, String> mappings, String body) {
    Map<String, CompiledMapping> compiled = new java.util.LinkedHashMap<>();
    mappings.forEach((k, v) -> compiled.put(k, CompiledMapping.compile(v)));
    PartnerRuntime runtime = new PartnerRuntime("acme", 1, PartnerTestSeeds.devSeed().getValue(), "partner-acme-v1",
        "direct:partner-acme-v1", compiled, Map.of(), List.of("sftp.acme.test"), Set.of("mw-sftp-sink"));
    Exchange exchange = new DefaultExchange(new DefaultCamelContext());
    exchange.setProperty(PartnerExchange.RUNTIME, runtime);
    exchange.getMessage().setBody(body);
    return exchange;
  }

  @Test
  void theMappingBuildsTheFileTextAndThePutAnswersWithTheFileName() {
    Exchange exchange = call(
        Map.of("PAY.file", "\"reference,amount\\n\" & ref & \",\" & $formatNumber(amount, '0.00')"),
        "{\"ref\":\"BITS01\",\"amount\":500}");

    step.content(exchange, "PAY.file");

    assertThat(exchange.getMessage().getBody(byte[].class))
        .isEqualTo("reference,amount\nBITS01,500.00".getBytes(StandardCharsets.UTF_8));
    assertThat(PartnerExchange.flag(exchange, PartnerExchange.SENT)).as("a failed put may have arrived").isTrue();

    exchange.getMessage().setHeader(Exchange.FILE_NAME_PRODUCED, "out/pay-20261118.csv");
    step.put(exchange);

    assertThat(exchange.getMessage().getBody(String.class)).isEqualTo("{\"ref\":\"BITS01\",\"amount\":500}");
    assertThat(exchange.getProperty(PartnerExchange.RESPONSE_STATUS, Integer.class)).isEqualTo(200);
    assertThat(exchange.getProperty(PartnerExchange.RESPONSE_BODY, String.class))
        .isEqualTo("{\"fileName\":\"pay-20261118.csv\"}");
  }

  @Test
  void aFixedWidthLineIsPaddedText() {
    Exchange exchange = call(Map.of("PAY.file", "$pad(ref, 12) & $pad($string(amount), -8, '0')"),
        "{\"ref\":\"BITS01\",\"amount\":500}");

    step.content(exchange, "PAY.file");

    assertThat(exchange.getMessage().getBody(String.class)).isEqualTo("BITS01      00000500");
  }

  @Test
  void aMappingThatMakesNoTextFailsWithoutTheBody() {
    Exchange exchange = call(Map.of("PAY.file", "{\"ref\": ref}"), "{\"ref\":\"01712345678\"}");

    assertThatThrownBy(() -> step.content(exchange, "PAY.file"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("text")
        .hasMessageNotContaining("01712345678");
  }

  @Test
  void withoutAMappingTheBodyIsWrittenAsItIs() {
    Exchange exchange = new DefaultExchange(new DefaultCamelContext());
    exchange.getMessage().setBody("reference,status\nBITS01,ACK\n");

    step.content(exchange, "");
    step.put(exchange);

    assertThat(exchange.getMessage().getBody(String.class)).isEqualTo("reference,status\nBITS01,ACK\n");
    assertThat(exchange.getProperty(PartnerExchange.RESPONSE_STATUS)).isNull();
  }
}

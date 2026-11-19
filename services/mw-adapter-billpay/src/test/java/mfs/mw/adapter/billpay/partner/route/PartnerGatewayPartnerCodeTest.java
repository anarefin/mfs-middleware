package mfs.mw.adapter.billpay.partner.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import mfs.mw.adapter.billpay.partner.PartnerCall;
import mfs.mw.adapter.billpay.partner.PartnerResponse;
import mfs.mw.adapter.billpay.partner.config.PartnerConfigStore;
import mfs.mw.adapter.billpay.partner.kamelet.MwAuditStep;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The gateway hands the route the partner-side code of the call as {@link
 * PartnerExchange#PARTNER_CODE}: {@code input.partner.billerCode}, else {@code productCode}, so a
 * route can branch per biller or product.
 */
class PartnerGatewayPartnerCodeTest {

  static final PartnerRuntime RUNTIME =
      new PartnerRuntime("billagg", 0, null, "r", "direct:partner-billagg-v0", Map.of(), Map.of(), List.of(), Set.of());

  @Test
  void theBillerCodeIsThePartnerCode() throws Exception {
    assertThat(header(Map.of("partner", Map.of("billerCode", "KGDCL", "productCode", "P1")))).isEqualTo("KGDCL");
  }

  @Test
  void theProductCodeIsThePartnerCodeWhenThereIsNoBillerCode() throws Exception {
    assertThat(header(Map.of("partner", Map.of("billerCode", " ", "productCode", "GP-100")))).isEqualTo("GP-100");
  }

  @Test
  void noCodeNoHeader() throws Exception {
    assertThat(header(Map.of("partner", Map.of("partnerId", "billagg")))).isNull();
    assertThat(header(Map.of())).isNull();
  }

  private static String header(Map<String, Object> input) throws Exception {
    ProducerTemplate producer = mock(ProducerTemplate.class);
    MwAuditStep results = mock(MwAuditStep.class);
    AtomicReference<Exchange> sent = new AtomicReference<>();
    try (DefaultCamelContext camel = new DefaultCamelContext()) {
      when(producer.send(eq(RUNTIME.endpoint()), any(Processor.class))).thenAnswer(inv -> {
        Exchange exchange = new DefaultExchange(camel);
        inv.<Processor>getArgument(1).process(exchange);
        sent.set(exchange);
        return exchange;
      });
      when(results.response(any(Exchange.class), anyString()))
          .thenReturn(new PartnerResponse.NotSent(PartnerResponse.NotSentReason.DISABLED));
      PartnerGateway gateway = new PartnerGateway(
          mock(PartnerConfigStore.class), producer, JsonMapper.builder().build(), results, new SimpleMeterRegistry(),
          CircuitBreakerRegistry.ofDefaults(), Clock.systemUTC(), null);

      gateway.dryRun(RUNTIME, new PartnerCall("billagg", "INQUIRY", "BILL_PAY", null, null, "BITS1", input));

      return sent.get().getMessage().getHeader(PartnerExchange.PARTNER_CODE, String.class);
    }
  }
}

package mfs.mw.adapter.billpay.route;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import mfs.mw.adapter.billpay.config.KafkaRetryProperties;
import mfs.mw.adapter.billpay.messaging.MwHeaders;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;

/** Parking a failed record stamps {@code mw-retry-not-before} from the injected clock. */
class RetryTopicRouteBuilderTest {

  static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");

  final RetryTopicRouteBuilder routes =
      new RetryTopicRouteBuilder(
          new KafkaRetryProperties(List.of(Duration.ofSeconds(10), Duration.ofMinutes(1))),
          Clock.fixed(NOW, ZoneOffset.UTC)) {
        @Override
        protected String topic() {
          return "it.event.v1";
        }

        @Override
        protected String handlerUri() {
          return "log:ignored";
        }
      };

  Exchange failed() {
    Exchange e = new DefaultExchange(new DefaultCamelContext());
    e.setProperty(Exchange.EXCEPTION_CAUGHT, new IllegalStateException("boom"));
    return e;
  }

  @Test
  void retryNotBeforeIsTheClockPlusThatRetrysDelay() {
    Exchange first = failed();
    routes.park(first, 1);
    assertThat(first.getMessage().getHeader(MwHeaders.RETRY_NOT_BEFORE))
        .isEqualTo(Long.toString(NOW.toEpochMilli() + 10_000));

    Exchange second = failed();
    routes.park(second, 2);
    assertThat(second.getMessage().getHeader(MwHeaders.RETRY_NOT_BEFORE))
        .isEqualTo(Long.toString(NOW.toEpochMilli() + 60_000));
    assertThat(second.getMessage().getHeader(MwHeaders.RETRY_ATTEMPT)).isEqualTo("2");
  }

  @Test
  void dlqRecordCarriesNoRetryNotBefore() {
    Exchange e = failed();
    e.getMessage().setHeader(MwHeaders.RETRY_NOT_BEFORE, "1");
    routes.park(e, 3);
    assertThat(e.getMessage().getHeader(MwHeaders.RETRY_NOT_BEFORE)).isNull();
    assertThat(e.getMessage().getHeader(MwHeaders.ERROR_CLASS))
        .isEqualTo(IllegalStateException.class.getName());
  }
}

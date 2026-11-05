package mfs.mw.ussd.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import mfs.mw.ussd.client.UssdSmppClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The SMPP USSD binds (spec 9.2, WP19): one bind group for every MNO whose {@code
 * mw.ussd.mno.<mno>.transport} is SMPP, with the endpoint of {@code mw.ussd.smpp.mno.<mno>}.
 * Switching an MNO between HTTP and SMPP is configuration only.
 */
@Configuration(proxyBeanMethods = false)
public class UssdSmppConfig {

  @Bean
  UssdSmppClient ussdSmppClient(UssdSmppProperties smpp, UssdProperties ussd, MeterRegistry meters, Clock clock) {
    Set<String> onSmpp = ussd.mno().entrySet().stream()
        .filter(e -> e.getValue().transport() == UssdProperties.Transport.SMPP)
        .map(Map.Entry::getKey)
        .collect(Collectors.toSet());
    return new UssdSmppClient(smpp, onSmpp, meters, clock);
  }
}

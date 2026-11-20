package mfs.mw.ussd.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import mfs.mw.ussd.client.UssdSmppClient;
import mfs.mw.ussd.transport.MnoTlvAdapter;
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
  UssdSmppClient ussdSmppClient(UssdSmppProperties smpp, UssdProperties ussd, List<MnoTlvAdapter> adapters,
      MeterRegistry meters, Clock clock) {
    Map<String, MnoTlvAdapter> byProfile =
        adapters.stream().collect(Collectors.toMap(MnoTlvAdapter::profile, Function.identity()));
    Set<String> onSmpp = ussd.mno().entrySet().stream()
        .filter(e -> e.getValue().transport() == UssdProperties.Transport.SMPP)
        .map(e -> e.getKey())
        .collect(Collectors.toSet());
    Map<String, MnoTlvAdapter> byMno = onSmpp.stream().collect(Collectors.toMap(Function.identity(), mno -> {
      String profile = ussd.mno().get(mno).tlvProfileOr(mno);
      MnoTlvAdapter adapter = byProfile.get(profile);
      if (adapter == null) {
        throw new IllegalStateException("no USSD TLV adapter for profile " + profile);
      }
      return adapter;
    }));
    return new UssdSmppClient(smpp, onSmpp, byMno, meters, clock);
  }
}

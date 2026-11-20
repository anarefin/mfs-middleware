package mfs.mw.orchestrator.config;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Airtime top-up (ticket 112b, FRS UC-08, FL-03). {@code inquiry-partner} is the operator OCS
 * profile a postpaid preview asks for the bill ({@code gp} to {@code gp-ocs}). {@code denominations}
 * is the prepaid amount list per operator, in poisha; an operator with no list accepts any positive
 * amount. {@code bill} is mw-adapter-telco's synchronous bill fetch.
 */
@ConfigurationProperties("mw.topup")
public record TopupProperties(Map<String, String> inquiryPartner, Map<String, List<Long>> denominations, Bill bill) {

  public TopupProperties {
    inquiryPartner = inquiryPartner == null ? Map.of() : Map.copyOf(inquiryPartner);
    if (denominations == null) {
      denominations = Map.of();
    } else {
      Map<String, List<Long>> copy = new LinkedHashMap<>();
      denominations.forEach((operator, amounts) -> copy.put(operator, amounts == null ? List.of() : List.copyOf(amounts)));
      denominations = Map.copyOf(copy);
    }
    if (bill == null) {
      bill = new Bill(URI.create("http://mw-adapter-telco:8080"), Duration.ofSeconds(1), Duration.ofSeconds(25));
    }
  }

  /** The OCS profile that fetches this operator's postpaid bill, or null when none is configured. */
  public String inquiryPartner(String operator) {
    return operator == null ? null : inquiryPartner.get(operator);
  }

  /** Prepaid amounts this operator sells, in poisha. Empty when the operator has no list. */
  public List<Long> denominationsOf(String operator) {
    if (operator == null) {
      return List.of();
    }
    List<Long> amounts = denominations.get(operator);
    return amounts == null ? List.of() : amounts;
  }

  /** mw-adapter-telco {@code POST /telco/v1/bills/fetch}. The read timeout sits above one OCS inquiry. */
  public record Bill(URI baseUrl, Duration connectTimeout, Duration readTimeout) {}
}

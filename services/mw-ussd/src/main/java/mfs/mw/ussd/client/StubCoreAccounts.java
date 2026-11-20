package mfs.mw.ussd.client;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import mfs.mw.ussd.service.CoreAccounts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Test double for {@link CoreAccounts}: every number is {@code CUSTOMER} until a test says
 * otherwise. Selected with {@code mw.ussd.core.mode=stub}.
 */
@Component
@ConditionalOnProperty(name = "mw.ussd.core.mode", havingValue = "stub")
public class StubCoreAccounts implements CoreAccounts {

  private final ConcurrentHashMap<String, String> types = new ConcurrentHashMap<>();

  public void setType(String msisdn, String type) {
    types.put(msisdn, type);
  }

  @Override
  public Optional<String> type(String msisdn) {
    return Optional.of(types.getOrDefault(msisdn, "CUSTOMER"));
  }
}

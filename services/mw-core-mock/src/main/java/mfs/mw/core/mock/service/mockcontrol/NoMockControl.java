package mfs.mw.core.mock.service.mockcontrol;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Without the mock-control profile: every call is decided by the ledger rules; no journal, no switch. */
@Service
@Profile("!" + MockControl.PROFILE)
public class NoMockControl implements MockControl {

  @Override
  public Decision decide(String operation, Map<String, String> keys) {
    return Decision.PASS;
  }

  @Override
  public void record(String operation, String commandType, String txnId, Map<String, String> keys) {
    // nothing kept outside the mock-control profile
  }
}

package mfs.mw.simulators.service;

import java.util.concurrent.atomic.AtomicReference;
import mfs.mw.simulators.dto.PayShieldScenario;
import mfs.mw.simulators.exception.ErrorCodes;
import mfs.mw.simulators.exception.MwException;
import org.springframework.stereotype.Service;

/** The payShield simulator's current scenario (ticket 64), in memory; none = normal answers. */
@Service
public class PayShieldScenarioStore {

  private static final PayShieldScenario NONE = new PayShieldScenario(null, null, null, null);

  private final AtomicReference<PayShieldScenario> current = new AtomicReference<>(NONE);

  public PayShieldScenario get() {
    return current.get();
  }

  public void set(PayShieldScenario scenario) {
    if (scenario.errorCode() != null && !scenario.errorCode().matches("[0-9A-Z]{2}")) {
      throw new MwException(ErrorCodes.VAL_INVALID, "errorCode must be two characters");
    }
    current.set(scenario);
  }

  public void reset() {
    current.set(NONE);
  }
}

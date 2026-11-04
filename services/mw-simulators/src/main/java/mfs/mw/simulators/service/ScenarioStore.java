package mfs.mw.simulators.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import mfs.mw.simulators.config.SimulatorProperties;
import mfs.mw.simulators.dto.Scenario;
import mfs.mw.simulators.exception.ErrorCodes;
import mfs.mw.simulators.exception.MwException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Current scenario per partner (spec 22.2 control API). In memory; a restart resets it. */
@Service
public class ScenarioStore {

  private final SimulatorProperties props;
  private final Map<String, Scenario> scenarios = new ConcurrentHashMap<>();

  public ScenarioStore(SimulatorProperties props) {
    this.props = props;
  }

  public boolean isPartner(String partner) {
    return props.partners().contains(partner);
  }

  public Scenario get(String partner) {
    require(partner);
    return scenarios.getOrDefault(partner, Scenario.DEFAULT);
  }

  public void set(String partner, Scenario scenario) {
    require(partner);
    scenarios.put(partner, scenario);
  }

  public void reset(String partner) {
    require(partner);
    scenarios.remove(partner);
  }

  public void require(String partner) {
    if (!isPartner(partner)) {
      throw new MwException(ErrorCodes.VAL_NOT_FOUND, "No simulator named " + partner)
          .withStatus(HttpStatus.NOT_FOUND);
    }
  }
}

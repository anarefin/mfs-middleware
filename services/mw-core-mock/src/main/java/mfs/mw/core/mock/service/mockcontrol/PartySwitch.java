package mfs.mw.core.mock.service.mockcontrol;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import mfs.mw.core.mock.dto.MockSettings;
import mfs.mw.core.mock.dto.MockSwitch;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Party-level state of the mock-control engine (FRS MCT-03; copied from mw-simulators, ticket 103): the
 * switch (UP, DOWN, MAINTENANCE, SLOW with a latency range) and the settings (timeout hold). In memory.
 */
@Service
@Profile(MockControl.PROFILE)
public class PartySwitch {

  private final Map<String, MockSwitch> switches = new ConcurrentHashMap<>();
  private final Map<String, MockSettings> settings = new ConcurrentHashMap<>();

  public MockSwitch get(String party) {
    MockParties.require(party);
    return switches.getOrDefault(party, MockSwitch.UP);
  }

  public void set(String party, MockSwitch s) {
    MockParties.require(party);
    switches.put(party, s);
  }

  public MockSettings settings(String party) {
    MockParties.require(party);
    return settings.getOrDefault(party, MockSettings.DEFAULT);
  }

  public void settings(String party, MockSettings s) {
    MockParties.require(party);
    settings.put(party, s);
  }

  /** Back to UP with the default settings. */
  public void reset(String party) {
    MockParties.require(party);
    switches.remove(party);
    settings.remove(party);
  }
}

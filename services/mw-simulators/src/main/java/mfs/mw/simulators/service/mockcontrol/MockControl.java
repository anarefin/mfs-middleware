package mfs.mw.simulators.service.mockcontrol;

import java.util.Map;
import mfs.mw.simulators.dto.MockSettings;
import mfs.mw.simulators.dto.SimMode;

/**
 * What a mock party asks before it handles a call (ADR D33): {@link ScenarioEngine} in the
 * {@value #PROFILE} profile (scenarios, switches, settings), else {@link MagicValuesOnly} (the magic
 * values alone, no control API).
 */
public interface MockControl {

  /** The Spring profile the mock-control engine and its API exist in (non-production only, MCT-08). */
  String PROFILE = "mock-control";

  /**
   * The behaviour of one call.
   *
   * @param party a name of {@link MockParties}
   * @param operation one of the party's operations
   * @param keys the call's scope keys (msisdn, account, biller, billNumber, txnRef, ...); values match a
   *     scenario's {@code key}
   * @param magic what the call's magic value (amount, MSISDN or account ending) says, SUCCESS if none
   */
  Decision decide(String party, String operation, Map<String, String> keys, SimMode magic);

  /** The party's settings (callback target, callback-only, timeout hold). */
  MockSettings settings(String party);
}

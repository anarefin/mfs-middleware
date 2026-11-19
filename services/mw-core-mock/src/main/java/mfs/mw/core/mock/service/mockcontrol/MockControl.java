package mfs.mw.core.mock.service.mockcontrol;

import java.util.Map;

/**
 * What the core asks before it handles a call (ADR D33, ticket 103; the core's copy of mw-simulators'
 * engine): {@link ScenarioEngine} in the {@value #PROFILE} profile (scenarios, switch, settings,
 * journal), else {@link NoMockControl} (every call decided by the ledger rules alone, no control API).
 */
public interface MockControl {

  /** The Spring profile the mock-control engine and its API exist in (non-production only, MCT-08). */
  String PROFILE = "mock-control";

  /** The one mock party of this service. */
  String CORE = "core";

  /**
   * The behaviour of one call of the core party. Inside a REST call ({@link RestCall}) the switch was
   * already checked by {@code filter/MockControlFilter}, and the decision is handed to it.
   *
   * @param operation one of {@link MockParties#CORE_PARTY}'s operations
   * @param keys the call's scope keys (wallet ids / MSISDNs, txnId, holdId, ...); values match a
   *     scenario's {@code key}
   */
  Decision decide(String operation, Map<String, String> keys);

  /** Notes one handled command in the core party's journal (MCK-07); nothing without the profile. */
  void record(String operation, String commandType, String txnId, Map<String, String> keys);
}

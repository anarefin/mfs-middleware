package mfs.mw.simulators.service.mockcontrol;

/**
 * State a mock party keeps (ledgers, reversals, received IPNs): {@code DELETE
 * /mock-control/{party}/journal} resets it with the journal (FRS MCT-06). References keep counting, so
 * no id is handed out twice.
 */
public interface PartyState {

  /** Forgets what was kept for {@code party}; does nothing for a party it does not keep. */
  void reset(String party);
}

package mfs.mw.orchestrator.client;

/**
 * The postpaid bill from an operator OCS, asked once at preview (ticket 112b, FRS FL-03). Confirm
 * does not call this: it pays against the bill the quote stored.
 */
public interface TelcoBillClient {

  /** Outstanding bill and minimum due, in poisha. */
  record Bill(long outstandingMinor, long minimumMinor) {}

  /**
   * Fetches the bill of {@code msisdn} from the operator profile {@code partnerId} ({@code gp-ocs}
   * and the rest). {@code correlationId} may be null.
   */
  Bill fetch(String partnerId, String msisdn, String correlationId);
}

package mfs.mw.crypto.entity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One key ceremony ({@code crypto.ceremony}, ticket 75): metadata only. {@code componentLabel} is
 * the HSM label of the first custodian's component while the second is awaited, {@code
 * stagedLabel} the combined key's label until commit; {@code shares} who entered what share when
 * ({@code crypto.custodian_share_log}).
 */
public record Ceremony(
    UUID ceremonyId,
    String keyId,
    String purpose,
    int version,
    State state,
    String componentLabel,
    String stagedLabel,
    String checkValue,
    Instant createdAt,
    Instant updatedAt,
    List<Share> shares) {

  public Ceremony {
    shares = List.copyOf(shares);
  }

  public enum State {
    /** Waiting for a component (none or one in). */
    OPEN,
    /** Both components in, the key combined in the HSM; waiting for the approval. */
    COMBINED,
    /** Approved: the key version is ACTIVE. */
    COMMITTED,
    /** Rejected, expired or cancelled: the staged HSM objects are destroyed. */
    ABORTED
  }

  /** Share {@code share} (1 or 2) was entered by {@code custodian} at {@code enteredAt}. */
  public record Share(int share, String custodian, Instant enteredAt) {}
}

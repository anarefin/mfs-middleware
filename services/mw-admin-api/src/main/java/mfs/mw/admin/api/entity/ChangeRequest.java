package mfs.mw.admin.api.entity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One row of {@code admin.change_request} (spec 15.3, ticket 37). {@code makerSub} / {@code
 * checkerSub} are token subjects (the maker != checker rule compares them and the usernames);
 * {@code payload} what is (or was) sent on approval, {@code diff} the resource state the maker saw
 * ({@code before}) and the one the action leads to ({@code after}).
 */
public record ChangeRequest(
    UUID id,
    String resourceType,
    String resourceId,
    String action,
    Map<String, Object> payload,
    Map<String, Object> diff,
    String makerId,
    String makerSub,
    Status status,
    String checkerId,
    String checkerSub,
    String reason,
    String decisionReason,
    Instant createdAt,
    Instant decidedAt,
    Instant expiresAt) {

  public static final String TRANSACTION = "transaction";
  /** A wallet (ticket 52): resourceId the wallet id (its MSISDN), masked in every view and audit. */
  public static final String WALLET = "wallet";

  public enum Status {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED
  }

  public ChangeRequest {
    payload = Map.copyOf(payload);
    diff = Map.copyOf(diff);
  }

  /** Whether the request can no longer be decided at {@code now} (expiresAt reached). */
  public boolean expiredAt(Instant now) {
    return !now.isBefore(expiresAt);
  }

  /** The status the maker saw, from {@code diff.before.status}. */
  public String statusBefore() {
    return side("before");
  }

  /** The status the action leads to, from {@code diff.after.status}. */
  public String statusAfter() {
    return side("after");
  }

  private String side(String key) {
    return diff.get(key) instanceof Map<?, ?> m && m.get("status") != null ? String.valueOf(m.get("status")) : null;
  }
}

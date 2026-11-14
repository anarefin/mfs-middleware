package mfs.mw.admin.api.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.admin.api.entity.ChangeRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@code admin.change_request} (own schema only, spec 16.2), plain JDBC. */
@Repository
public class ChangeRequestRepository {

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private static final String COLUMNS =
      "id, resource_type, resource_id, action, payload::text as payload, diff::text as diff, maker_id, maker_sub,"
          + " status, checker_id, checker_sub, reason, decision_reason, created_at, decided_at, expires_at";

  private final JdbcTemplate jdbc;
  private final JsonMapper json;
  private final RowMapper<ChangeRequest> rows = this::row;

  public ChangeRequestRepository(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /**
   * Inserts a PENDING request.
   *
   * @return false when the resource already has a PENDING request (one open request per resource)
   */
  public boolean insert(ChangeRequest cr) {
    try {
      jdbc.update(
          "insert into change_request (id, resource_type, resource_id, action, payload, diff, maker_id, maker_sub,"
              + " status, reason, created_at, expires_at) values (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?)",
          cr.id(), cr.resourceType(), cr.resourceId(), cr.action(), json.writeValueAsString(cr.payload()),
          json.writeValueAsString(cr.diff()), cr.makerId(), cr.makerSub(), cr.status().name(), cr.reason(),
          Timestamp.from(cr.createdAt()), Timestamp.from(cr.expiresAt()));
      return true;
    } catch (DuplicateKeyException e) {
      return false;
    }
  }

  public Optional<ChangeRequest> find(UUID id) {
    return jdbc.query("select " + COLUMNS + " from change_request where id = ?", rows, id).stream().findFirst();
  }

  /**
   * Records the decision on a request that is still PENDING (the guard against two checkers
   * deciding at once).
   *
   * @return false when it is no longer PENDING
   */
  public boolean decide(
      UUID id, ChangeRequest.Status to, String checkerId, String checkerSub, String decisionReason,
      Map<String, Object> payload, Instant at) {
    return jdbc.update(
            "update change_request set status = ?, checker_id = ?, checker_sub = ?, decision_reason = ?,"
                + " payload = ?::jsonb, decided_at = ? where id = ? and status = 'PENDING'",
            to.name(), checkerId, checkerSub, decisionReason, json.writeValueAsString(payload), Timestamp.from(at), id)
        == 1;
  }

  /**
   * Replaces the payload of a request that is still PENDING (ticket 75: a key ceremony's shares).
   *
   * @return false when it is no longer PENDING
   */
  public boolean updatePendingPayload(UUID id, Map<String, Object> payload) {
    return jdbc.update(
            "update change_request set payload = ?::jsonb where id = ? and status = 'PENDING'",
            json.writeValueAsString(payload), id)
        == 1;
  }

  /** The claim of an APPROVING request: holder, token, when taken (V8). */
  public record Claim(String checkerId, String checkerSub, UUID token, Instant claimedAt) {}

  /**
   * Claim-first approval (ticket 75): PENDING -> APPROVING for this checker with a fresh claim
   * {@code token}, in one conditional update; only the winner goes on to call the other service.
   *
   * @return false when it is no longer PENDING (decided, or claimed by another approval)
   */
  public boolean claim(UUID id, String checkerId, String checkerSub, UUID token, Instant at) {
    return jdbc.update(
            "update change_request set status = 'APPROVING', checker_id = ?, checker_sub = ?, claim_token = ?,"
                + " claimed_at = ?, attempt_started_at = ? where id = ? and status = 'PENDING'",
            checkerId, checkerSub, token, Timestamp.from(at), Timestamp.from(at), id)
        == 1;
  }

  /** The claim of request {@code id} while it is APPROVING. */
  public Optional<Claim> claimOf(UUID id) {
    return jdbc.query(
            "select checker_id, checker_sub, claim_token, claimed_at from change_request where id = ? and status = 'APPROVING'",
            (rs, i) -> new Claim(rs.getString("checker_id"), rs.getString("checker_sub"),
                rs.getObject("claim_token", UUID.class), instant(rs, "claimed_at")),
            id)
        .stream().findFirst();
  }

  /** The holder starts another attempt (a retry): refreshes {@code attempt_started_at}. */
  public boolean touchAttempt(UUID id, Claim claim, Instant at) {
    return jdbc.update(
            "update change_request set attempt_started_at = ? where id = ? and status = 'APPROVING'"
                + " and checker_sub = ? and claim_token = ?",
            Timestamp.from(at), id, claim.checkerSub(), claim.token())
        == 1;
  }

  /** APPROVING -> APPROVED by the claim holder (sub and token) with the final payload. */
  public boolean finishApproved(UUID id, Claim claim, Map<String, Object> payload, Instant at) {
    return jdbc.update(
            "update change_request set status = 'APPROVED', payload = ?::jsonb, decided_at = ?, claim_token = null"
                + " where id = ? and status = 'APPROVING' and checker_sub = ? and claim_token = ?",
            json.writeValueAsString(payload), Timestamp.from(at), id, claim.checkerSub(), claim.token())
        == 1;
  }

  /** APPROVING -> PENDING by the claim holder, once the other service said the change was not applied. */
  public boolean release(UUID id, Claim claim) {
    return jdbc.update(
            "update change_request set status = 'PENDING', checker_id = null, checker_sub = null, claim_token = null,"
                + " claimed_at = null, attempt_started_at = null"
                + " where id = ? and status = 'APPROVING' and checker_sub = ? and claim_token = ?",
            id, claim.checkerSub(), claim.token())
        == 1;
  }

  /** APPROVING -> EXPIRED (reconciler: not applied and past {@code expires_at}). */
  public boolean expireClaimed(UUID id, Claim claim, Instant at) {
    return jdbc.update(
            "update change_request set status = 'EXPIRED', checker_id = null, checker_sub = null, claim_token = null,"
                + " decided_at = ? where id = ? and status = 'APPROVING' and checker_sub = ? and claim_token = ?",
            Timestamp.from(at), id, claim.checkerSub(), claim.token())
        == 1;
  }

  /** APPROVING requests whose last attempt started before {@code before}, oldest first. */
  public List<ChangeRequest> staleApproving(Instant before, int limit) {
    return jdbc.query(
        "select " + COLUMNS + " from change_request where status = 'APPROVING' and attempt_started_at < ?"
            + " order by attempt_started_at limit ?",
        rows, Timestamp.from(before), limit);
  }

  /** PENDING requests whose {@code expires_at} is at or before {@code now}, oldest first, locked. */
  public List<ChangeRequest> duePendingForUpdate(Instant now, int limit) {
    return jdbc.query(
        "select " + COLUMNS + " from change_request where status = 'PENDING' and expires_at <= ?"
            + " order by expires_at limit ? for update skip locked",
        rows, Timestamp.from(now), limit);
  }

  /** Newest first, only the given resource types (an operator's visible ones); null filters are left out. */
  public List<ChangeRequest> listTypes(
      ChangeRequest.Status status, java.util.Collection<String> resourceTypes, String resourceId, int limit, int offset) {
    StringBuilder sql = new StringBuilder("select " + COLUMNS + " from change_request where resource_type = any (?)");
    List<Object> args = new ArrayList<>();
    args.add(resourceTypes.toArray(String[]::new));
    if (status != null) {
      sql.append(" and status = ?");
      args.add(status.name());
    }
    if (resourceId != null) {
      sql.append(" and resource_id = ?");
      args.add(resourceId);
    }
    sql.append(" order by created_at desc, id limit ? offset ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql.toString(), rows, args.toArray());
  }

  /** Newest first; null filters are left out. */
  public List<ChangeRequest> list(
      ChangeRequest.Status status, String resourceType, String resourceId, int limit, int offset) {
    StringBuilder sql = new StringBuilder("select " + COLUMNS + " from change_request where true");
    List<Object> args = new ArrayList<>();
    if (status != null) {
      sql.append(" and status = ?");
      args.add(status.name());
    }
    if (resourceType != null) {
      sql.append(" and resource_type = ?");
      args.add(resourceType);
    }
    if (resourceId != null) {
      sql.append(" and resource_id = ?");
      args.add(resourceId);
    }
    sql.append(" order by created_at desc, id limit ? offset ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql.toString(), rows, args.toArray());
  }

  private ChangeRequest row(ResultSet rs, int i) throws SQLException {
    return new ChangeRequest(
        rs.getObject("id", UUID.class),
        rs.getString("resource_type"),
        rs.getString("resource_id"),
        rs.getString("action"),
        json.readValue(rs.getString("payload"), MAP),
        json.readValue(rs.getString("diff"), MAP),
        rs.getString("maker_id"),
        rs.getString("maker_sub"),
        ChangeRequest.Status.valueOf(rs.getString("status")),
        rs.getString("checker_id"),
        rs.getString("checker_sub"),
        rs.getString("reason"),
        rs.getString("decision_reason"),
        instant(rs, "created_at"),
        instant(rs, "decided_at"),
        instant(rs, "expires_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }
}

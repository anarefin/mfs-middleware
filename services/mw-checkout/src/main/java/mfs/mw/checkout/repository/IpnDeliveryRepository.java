package mfs.mw.checkout.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import mfs.mw.checkout.entity.IpnDelivery;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code checkout.ipn_delivery} (spec 16.2, ticket 32): one row per IPN attempt. */
@Repository
public class IpnDeliveryRepository {

  private static final String COLUMNS =
      "delivery_id, session_id, merchant_id, attempt, ipn_url, payload, status, due_at, attempted_at,"
          + " http_status, error, duration_ms, created_at";

  private final JdbcClient jdbc;

  public IpnDeliveryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(IpnDelivery d) {
    jdbc.sql("insert into ipn_delivery (" + COLUMNS + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
        .params(
            d.deliveryId(), d.sessionId(), d.merchantId(), d.attempt(), d.ipnUrl(), d.payload(), d.status().name(),
            ts(d.dueAt()), ts(d.attemptedAt()), d.httpStatus(), d.error(), d.durationMs(), ts(d.createdAt()))
        .update();
  }

  /** PENDING attempts due at {@code now}, oldest first. */
  public List<IpnDelivery> findDue(Instant now, int limit) {
    return jdbc.sql(
            "select " + COLUMNS + " from ipn_delivery where status = 'PENDING' and due_at <= ?"
                + " order by due_at limit ?")
        .params(ts(now), limit)
        .query(IpnDeliveryRepository::map)
        .list();
  }

  /** Every attempt for a session, in order. */
  public List<IpnDelivery> findBySession(UUID sessionId) {
    return jdbc.sql("select " + COLUMNS + " from ipn_delivery where session_id = ? order by attempt")
        .param(sessionId)
        .query(IpnDeliveryRepository::map)
        .list();
  }

  /** Records how a PENDING attempt went; false when it was no longer PENDING. */
  public boolean recordAttempt(
      UUID deliveryId, IpnDelivery.Status status, Instant attemptedAt, Integer httpStatus, String error,
      long durationMs) {
    return jdbc.sql(
                "update ipn_delivery set status = ?, attempted_at = ?, http_status = ?, error = ?, duration_ms = ?"
                    + " where delivery_id = ? and status = 'PENDING'")
            .params(status.name(), ts(attemptedAt), httpStatus, error, durationMs, deliveryId)
            .update()
        == 1;
  }

  private static IpnDelivery map(ResultSet rs, int row) throws SQLException {
    return new IpnDelivery(
        rs.getObject("delivery_id", UUID.class),
        rs.getObject("session_id", UUID.class),
        rs.getString("merchant_id"),
        rs.getInt("attempt"),
        rs.getString("ipn_url"),
        rs.getString("payload"),
        IpnDelivery.Status.valueOf(rs.getString("status")),
        instant(rs.getTimestamp("due_at")),
        instant(rs.getTimestamp("attempted_at")),
        rs.getObject("http_status", Integer.class),
        rs.getString("error"),
        rs.getObject("duration_ms", Long.class),
        instant(rs.getTimestamp("created_at")));
  }

  private static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  private static Instant instant(Timestamp t) {
    return t == null ? null : t.toInstant();
  }
}

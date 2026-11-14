package mfs.mw.crypto.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.crypto.entity.Ceremony;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code crypto.ceremony} and {@code crypto.custodian_share_log} (V5__key_ceremony.sql). */
@Repository
public class CeremonyRepository {

  private static final String COLUMNS =
      "ceremony_id, key_id, purpose, version, state, component_label, staged_label, check_value, created_at, updated_at";

  private final JdbcClient jdbc;

  public CeremonyRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Ceremony> find(UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM ceremony WHERE ceremony_id = :id")
        .param("id", id)
        .query((rs, n) -> row(rs))
        .optional()
        .map(c -> withShares(c));
  }

  /** Like {@link #find}, row-locked until the transaction ends. */
  public Optional<Ceremony> lock(UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM ceremony WHERE ceremony_id = :id FOR UPDATE")
        .param("id", id)
        .query((rs, n) -> row(rs))
        .optional()
        .map(c -> withShares(c));
  }

  /** A new OPEN ceremony; a live one for the same key throws DuplicateKeyException. */
  public void insert(UUID id, String keyId, String purpose, int version, Instant now) {
    jdbc.sql("INSERT INTO ceremony (" + COLUMNS + ") VALUES (:id, :k, :p, :v, 'OPEN', NULL, NULL, NULL, :t, :t)")
        .param("id", id)
        .param("k", keyId)
        .param("p", purpose)
        .param("v", version)
        .param("t", Timestamp.from(now))
        .update();
  }

  /** An ABORTED tombstone for an id never opened (abort is terminal per id); a duplicate id throws. */
  public void insertAborted(UUID id, Instant now) {
    jdbc.sql("INSERT INTO ceremony (" + COLUMNS + ") VALUES (:id, NULL, NULL, NULL, 'ABORTED', NULL, NULL, NULL, :t, :t)")
        .param("id", id)
        .param("t", Timestamp.from(now))
        .update();
  }

  public void update(UUID id, Ceremony.State state, String componentLabel, String stagedLabel, String checkValue, Instant now) {
    jdbc.sql("UPDATE ceremony SET state = :s, component_label = :c, staged_label = :l, check_value = :kcv, updated_at = :t"
            + " WHERE ceremony_id = :id")
        .param("s", state.name())
        .param("c", componentLabel)
        .param("l", stagedLabel)
        .param("kcv", checkValue)
        .param("t", Timestamp.from(now))
        .param("id", id)
        .update();
  }

  /** Who entered share {@code share} and when; never the component. */
  public void logShare(UUID id, int share, String custodian, Instant at) {
    jdbc.sql("INSERT INTO custodian_share_log (ceremony_id, share, custodian, entered_at) VALUES (:id, :s, :c, :t)")
        .param("id", id)
        .param("s", share)
        .param("c", custodian)
        .param("t", Timestamp.from(at))
        .update();
  }

  private Ceremony withShares(Ceremony c) {
    List<Ceremony.Share> shares =
        jdbc.sql("SELECT share, custodian, entered_at FROM custodian_share_log WHERE ceremony_id = :id ORDER BY share")
            .param("id", c.ceremonyId())
            .query((rs, n) -> new Ceremony.Share(rs.getInt("share"), rs.getString("custodian"),
                rs.getTimestamp("entered_at").toInstant()))
            .list();
    return new Ceremony(c.ceremonyId(), c.keyId(), c.purpose(), c.version(), c.state(), c.componentLabel(),
        c.stagedLabel(), c.checkValue(), c.createdAt(), c.updatedAt(), shares);
  }

  private static Ceremony row(ResultSet rs) throws SQLException {
    return new Ceremony(
        rs.getObject("ceremony_id", UUID.class),
        rs.getString("key_id"),
        rs.getString("purpose"),
        (Integer) rs.getObject("version"),
        Ceremony.State.valueOf(rs.getString("state")),
        rs.getString("component_label"),
        rs.getString("staged_label"),
        rs.getString("check_value"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        List.of());
  }
}

package mfs.mw.crypto.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import mfs.mw.crypto.entity.CryptoKey;
import mfs.mw.crypto.entity.KeyState;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code crypto.key}: key version metadata (V4__key.sql). */
@Repository
public class KeyRepository {

  private static final String COLUMNS =
      "key_id, version, purpose, algorithm, provider, hsm_label, state, created_at, rotated_at";

  private final JdbcClient jdbc;

  public KeyRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Every version of every key, by keyId then version. */
  public List<CryptoKey> findAll() {
    return jdbc.sql("SELECT " + COLUMNS + " FROM key ORDER BY key_id, version").query(KeyRepository::row).list();
  }

  /** Every version of one key, oldest first. */
  public List<CryptoKey> findVersions(String keyId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM key WHERE key_id = :id ORDER BY version")
        .param("id", keyId)
        .query(KeyRepository::row)
        .list();
  }

  /** Like {@link #findVersions}, row-locked until the transaction ends (rotation). */
  public List<CryptoKey> lockVersions(String keyId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM key WHERE key_id = :id ORDER BY version FOR UPDATE")
        .param("id", keyId)
        .query(KeyRepository::row)
        .list();
  }

  /** Every version of every key of one purpose, by keyId then version. */
  public List<CryptoKey> findByPurpose(String purpose) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM key WHERE purpose = :p ORDER BY key_id, version")
        .param("p", purpose)
        .query(KeyRepository::row)
        .list();
  }

  /** The purposes that have at least one key. */
  public List<String> purposes() {
    return jdbc.sql("SELECT DISTINCT purpose FROM key").query(String.class).list();
  }

  /** Inserts a version; a duplicate (keyId, version) or HSM label throws DuplicateKeyException. */
  public void insert(CryptoKey k) {
    jdbc.sql("INSERT INTO key (" + COLUMNS + ") VALUES (:id, :v, :p, :a, :pr, :l, :s, :c, :r)")
        .param("id", k.keyId())
        .param("v", k.version())
        .param("p", k.purpose())
        .param("a", k.algorithm())
        .param("pr", k.provider())
        .param("l", k.hsmLabel())
        .param("s", k.state().name())
        .param("c", Timestamp.from(k.createdAt()))
        .param("r", k.rotatedAt() == null ? null : Timestamp.from(k.rotatedAt()))
        .update();
  }

  /** Moves one version to {@code state}, stamping {@code at} as its rotated_at. */
  public void updateState(String keyId, int version, KeyState state, Instant at) {
    jdbc.sql("UPDATE key SET state = :s, rotated_at = :r WHERE key_id = :id AND version = :v")
        .param("s", state.name())
        .param("r", Timestamp.from(at))
        .param("id", keyId)
        .param("v", version)
        .update();
  }

  private static CryptoKey row(ResultSet rs, int n) throws SQLException {
    Timestamp rotated = rs.getTimestamp("rotated_at");
    return new CryptoKey(
        rs.getString("key_id"),
        rs.getInt("version"),
        rs.getString("purpose"),
        rs.getString("algorithm"),
        rs.getString("provider"),
        rs.getString("hsm_label"),
        KeyState.valueOf(rs.getString("state")),
        rs.getTimestamp("created_at").toInstant(),
        rotated == null ? null : rotated.toInstant());
  }
}

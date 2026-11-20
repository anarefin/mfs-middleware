package mfs.mw.auth.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.auth.entity.RefreshToken;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@code refresh_token} (D21; own schema only): tokens stored as their SHA-256, one family per
 * session. Rotation is one conditional statement, so a token is rotated at most once however many
 * requests carry it.
 */
@Repository
public class RefreshTokenRepository {

  private static final String COLUMNS =
      "token_hash, family, wallet_id, device_id, expires_at, rotated_at, revoked, amr";
  private static final RowMapper<RefreshToken> MAPPER = (rs, i) -> row(rs);

  private final JdbcTemplate jdbc;

  public RefreshTokenRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(String tokenHash, UUID family, String walletId, String deviceId, Instant expiresAt, List<String> amr) {
    jdbc.update(
        "insert into refresh_token (token_hash, family, wallet_id, device_id, expires_at, amr) values (?, ?, ?, ?, ?, ?)",
        tokenHash, family, walletId, deviceId, Timestamp.from(expiresAt), String.join(",", amr));
  }

  /**
   * Marks the token rotated if it is current (not rotated, not revoked, not expired) and returns it;
   * empty, changing nothing, otherwise.
   */
  public Optional<RefreshToken> rotate(String tokenHash, Instant now) {
    return jdbc.query(
            "update refresh_token set rotated_at = ? where token_hash = ? and rotated_at is null and not revoked"
                + " and expires_at > ? returning " + COLUMNS,
            MAPPER, Timestamp.from(now), tokenHash, Timestamp.from(now))
        .stream()
        .findFirst();
  }

  public Optional<RefreshToken> find(String tokenHash) {
    return jdbc.query("select " + COLUMNS + " from refresh_token where token_hash = ?", MAPPER, tokenHash)
        .stream()
        .findFirst();
  }

  /** Revokes every token of the family (a session: logout, reuse of a rotated token). */
  public int revokeFamily(UUID family) {
    return jdbc.update("update refresh_token set revoked = true where family = ? and not revoked", family);
  }

  /** Revokes every session of the wallet (a PIN reset, an ops unbind). */
  public int revokeWallet(String walletId) {
    return jdbc.update("update refresh_token set revoked = true where wallet_id = ? and not revoked", walletId);
  }

  /** Revokes the refresh tokens of one device, leaving the wallet's other sessions. */
  public int revokeDevice(String walletId, String deviceId) {
    return jdbc.update(
        "update refresh_token set revoked = true where wallet_id = ? and device_id = ? and not revoked",
        walletId, deviceId);
  }

  /** Whether the family still has a current token (the session was not logged out or revoked). */
  public boolean familyLive(UUID family, Instant now) {
    Integer n = jdbc.queryForObject(
        "select count(*) from refresh_token where family = ? and not revoked and rotated_at is null and expires_at > ?",
        Integer.class, family, Timestamp.from(now));
    return n != null && n > 0;
  }

  private static RefreshToken row(ResultSet rs) throws SQLException {
    Timestamp rotated = rs.getTimestamp("rotated_at");
    return new RefreshToken(
        rs.getString("token_hash"),
        rs.getObject("family", UUID.class),
        rs.getString("wallet_id"),
        rs.getString("device_id"),
        rs.getTimestamp("expires_at").toInstant(),
        rotated == null ? null : rotated.toInstant(),
        rs.getBoolean("revoked"),
        Arrays.asList(rs.getString("amr").split(",")));
  }
}

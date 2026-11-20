package mfs.mw.ussd.repository;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The biller catalogue projection ({@code ussd.biller_catalog}, ticket 113b): one row per biller
 * code with the admin version and the published value as JSON text.
 */
@Repository
public class BillerCatalogRepository {

  /** One stored entry. */
  public record Row(String code, long version, String value) {}

  private final JdbcTemplate jdbc;

  public BillerCatalogRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Stores the entry unless a newer version is held; true when stored. */
  public boolean upsertIfNewer(String code, long version, String valueJson) {
    return jdbc.update(
            """
            INSERT INTO biller_catalog (code, config_version, value, updated_at)
            VALUES (?, ?, ?::jsonb, now())
            ON CONFLICT (code) DO UPDATE
               SET config_version = EXCLUDED.config_version, value = EXCLUDED.value, updated_at = now()
             WHERE biller_catalog.config_version <= EXCLUDED.config_version
            """,
            code,
            version,
            valueJson)
        > 0;
  }

  /** Removes the entry unless a newer version is held. */
  public void deleteIfNotNewer(String code, long version) {
    jdbc.update("DELETE FROM biller_catalog WHERE code = ? AND config_version <= ?", code, version);
  }

  public List<Row> all() {
    return jdbc.query(
        "SELECT code, config_version, value::text AS value FROM biller_catalog ORDER BY code",
        (rs, i) -> new Row(rs.getString("code"), rs.getLong("config_version"), rs.getString("value")));
  }
}

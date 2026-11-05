package mfs.mw.ussd.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import mfs.mw.ussd.entity.MenuVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Menu versions ({@code ussd.menu}) and their per-MNO activations with a go-live time
 * ({@code ussd.menu_activation}, spec 9.2).
 */
@Repository
public class MenuRepository {

  private static final RowMapper<MenuVersion> ROW =
      (rs, i) -> new MenuVersion(rs.getString("menu_id"), rs.getInt("version"), rs.getString("tree"));

  private final JdbcTemplate jdbc;

  public MenuRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The version live for this MNO at {@code at}: the latest activation whose go-live has passed. */
  public Optional<MenuVersion> active(String mno, Instant at) {
    List<MenuVersion> rows =
        jdbc.query(
            """
            SELECT m.menu_id, m.version, m.tree::text AS tree
              FROM menu_activation a JOIN menu m ON m.menu_id = a.menu_id AND m.version = a.version
             WHERE a.mno = ? AND a.go_live_at <= ?
             ORDER BY a.go_live_at DESC LIMIT 1
            """,
            ROW,
            mno,
            Timestamp.from(at));
    return rows.stream().findFirst();
  }

  public Optional<MenuVersion> find(String menuId, int version) {
    return jdbc.query(
            "SELECT menu_id, version, tree::text AS tree FROM menu WHERE menu_id = ? AND version = ?",
            ROW,
            menuId,
            version)
        .stream()
        .findFirst();
  }

  /** Stores a version unless it exists (versions are immutable). */
  public boolean insertIfAbsent(String menuId, int version, String tree) {
    return jdbc.update(
            "INSERT INTO menu (menu_id, version, tree) VALUES (?, ?, ?::jsonb) ON CONFLICT DO NOTHING",
            menuId,
            version,
            tree)
        == 1;
  }

  /**
   * The highest admin version (ticket 39) stored for {@code menuId}; empty when none was published
   * through the admin console.
   */
  public Optional<Long> latestConfigVersion(String menuId) {
    return Optional.ofNullable(
        jdbc.queryForObject("SELECT max(config_version) FROM menu WHERE menu_id = ?", Long.class, menuId));
  }

  /** The number a newly published version of {@code menuId} gets: above every stored one, at least {@code floor}. */
  public int nextPublishedVersion(String menuId, int floor) {
    Integer max = jdbc.queryForObject("SELECT max(version) FROM menu WHERE menu_id = ?", Integer.class, menuId);
    return Math.max(max == null ? 1 : max + 1, floor);
  }

  /** Stores a version published by the admin console with its admin version. */
  public void insertPublished(String menuId, int version, long configVersion, String tree) {
    jdbc.update(
        "INSERT INTO menu (menu_id, version, tree, config_version) VALUES (?, ?, ?::jsonb, ?)",
        menuId,
        version,
        tree,
        configVersion);
  }

  /** Schedules a version to go live for an MNO. */
  public void activate(String mno, String menuId, int version, Instant goLiveAt) {
    jdbc.update(
        "INSERT INTO menu_activation (mno, menu_id, version, go_live_at) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
        mno,
        menuId,
        version,
        Timestamp.from(goLiveAt));
  }

  /** The MNO's latest activation, live or still scheduled; empty when it has none. */
  public Optional<MenuVersion> latestActivation(String mno) {
    return jdbc.query(
            """
            SELECT m.menu_id, m.version, m.tree::text AS tree
              FROM menu_activation a JOIN menu m ON m.menu_id = a.menu_id AND m.version = a.version
             WHERE a.mno = ?
             ORDER BY a.go_live_at DESC LIMIT 1
            """,
            ROW,
            mno)
        .stream()
        .findFirst();
  }

  public boolean hasActivation(String mno) {
    Integer n = jdbc.queryForObject("SELECT count(*) FROM menu_activation WHERE mno = ?", Integer.class, mno);
    return n != null && n > 0;
  }
}

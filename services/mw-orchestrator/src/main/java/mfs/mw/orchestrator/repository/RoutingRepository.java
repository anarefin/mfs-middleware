package mfs.mw.orchestrator.repository;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import mfs.mw.orchestrator.service.routing.MaintenanceMessage;
import mfs.mw.orchestrator.service.routing.MnpOverride;
import mfs.mw.orchestrator.service.routing.MsisdnPrefix;
import mfs.mw.orchestrator.service.routing.RoutingRule;
import mfs.mw.orchestrator.service.routing.RoutingSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The routing tables (ticket 98). Applied from {@code mw.config.changed.v1} and read back on reload. */
@Repository
public class RoutingRepository {

  private final JdbcTemplate jdbc;

  public RoutingRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public RoutingSnapshot load() {
    return new RoutingSnapshot(rules(), prefixes(), overrides(), messages(), java.util.Map.of());
  }

  public List<RoutingRule> rules() {
    return jdbc.query(
        "select id, txn_type, product_code, biller_code, bank_code, operator_code, amount_min_minor,"
            + " amount_max_minor, channel, primary_partner_id, fallbacks, priority, enabled, version"
            + " from routing_rule",
        (rs, i) ->
            new RoutingRule(
                rs.getString("id"),
                rs.getString("txn_type"),
                rs.getString("product_code"),
                rs.getString("biller_code"),
                rs.getString("bank_code"),
                rs.getString("operator_code"),
                boxed(rs, "amount_min_minor"),
                boxed(rs, "amount_max_minor"),
                rs.getString("channel"),
                rs.getString("primary_partner_id"),
                texts(rs, "fallbacks"),
                rs.getInt("priority"),
                rs.getBoolean("enabled"),
                rs.getInt("version")));
  }

  public void upsert(RoutingRule rule) {
    jdbc.update(
        (Connection con) -> {
          PreparedStatement ps =
              con.prepareStatement(
                  "insert into routing_rule (id, txn_type, product_code, biller_code, bank_code, operator_code,"
                      + " amount_min_minor, amount_max_minor, channel, primary_partner_id, fallbacks, priority,"
                      + " enabled, version) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                      + " on conflict (id) do update set txn_type = excluded.txn_type,"
                      + " product_code = excluded.product_code, biller_code = excluded.biller_code,"
                      + " bank_code = excluded.bank_code, operator_code = excluded.operator_code,"
                      + " amount_min_minor = excluded.amount_min_minor, amount_max_minor = excluded.amount_max_minor,"
                      + " channel = excluded.channel, primary_partner_id = excluded.primary_partner_id,"
                      + " fallbacks = excluded.fallbacks, priority = excluded.priority, enabled = excluded.enabled,"
                      + " version = excluded.version where routing_rule.version < excluded.version");
          ps.setString(1, rule.id());
          ps.setString(2, rule.txnType());
          ps.setString(3, rule.productCode());
          ps.setString(4, rule.billerCode());
          ps.setString(5, rule.bankCode());
          ps.setString(6, rule.operatorCode());
          setLong(ps, 7, rule.amountMinMinor());
          setLong(ps, 8, rule.amountMaxMinor());
          ps.setString(9, rule.channel());
          ps.setString(10, rule.primaryPartnerId());
          ps.setArray(11, con.createArrayOf("text", rule.fallbacks().toArray(String[]::new)));
          ps.setInt(12, rule.priority());
          ps.setBoolean(13, rule.enabled());
          ps.setInt(14, rule.version());
          return ps;
        });
  }

  public void deleteRule(String id, int version) {
    jdbc.update("delete from routing_rule where id = ? and version <= ?", id, version);
  }

  public List<MsisdnPrefix> prefixes() {
    return jdbc.query(
        "select prefix, mno, enabled, version from msisdn_prefix",
        (rs, i) -> new MsisdnPrefix(rs.getString("prefix"), rs.getString("mno"), rs.getBoolean("enabled"), rs.getInt("version")));
  }

  public void upsert(MsisdnPrefix prefix) {
    jdbc.update(
        "insert into msisdn_prefix (prefix, mno, enabled, version) values (?, ?, ?, ?)"
            + " on conflict (prefix) do update set mno = excluded.mno, enabled = excluded.enabled,"
            + " version = excluded.version where msisdn_prefix.version < excluded.version",
        prefix.prefix(), prefix.mno(), prefix.enabled(), prefix.version());
  }

  public void deletePrefix(String prefix, int version) {
    jdbc.update("delete from msisdn_prefix where prefix = ? and version <= ?", prefix, version);
  }

  public List<MnpOverride> overrides() {
    return jdbc.query(
        "select msisdn, mno, enabled, version from mnp_override",
        (rs, i) -> new MnpOverride(rs.getString("msisdn"), rs.getString("mno"), rs.getBoolean("enabled"), rs.getInt("version")));
  }

  public void upsert(MnpOverride override) {
    jdbc.update(
        "insert into mnp_override (msisdn, mno, enabled, version) values (?, ?, ?, ?)"
            + " on conflict (msisdn) do update set mno = excluded.mno, enabled = excluded.enabled,"
            + " version = excluded.version where mnp_override.version < excluded.version",
        override.msisdn(), override.mno(), override.enabled(), override.version());
  }

  public void deleteOverride(String msisdn, int version) {
    jdbc.update("delete from mnp_override where msisdn = ? and version <= ?", msisdn, version);
  }

  public List<MaintenanceMessage> messages() {
    return jdbc.query(
        "select id, partner_id, biller_code, message_en, message_bn, valid_from, valid_until, enabled, version"
            + " from maintenance_message",
        (rs, i) ->
            new MaintenanceMessage(
                rs.getString("id"),
                rs.getString("partner_id"),
                rs.getString("biller_code"),
                rs.getString("message_en"),
                rs.getString("message_bn"),
                instant(rs, "valid_from"),
                instant(rs, "valid_until"),
                rs.getBoolean("enabled"),
                rs.getInt("version")));
  }

  public void upsert(MaintenanceMessage message) {
    jdbc.update(
        "insert into maintenance_message (id, partner_id, biller_code, message_en, message_bn, valid_from,"
            + " valid_until, enabled, version) values (?, ?, ?, ?, ?, ?, ?, ?, ?)"
            + " on conflict (id) do update set partner_id = excluded.partner_id, biller_code = excluded.biller_code,"
            + " message_en = excluded.message_en, message_bn = excluded.message_bn,"
            + " valid_from = excluded.valid_from, valid_until = excluded.valid_until,"
            + " enabled = excluded.enabled, version = excluded.version"
            + " where maintenance_message.version < excluded.version",
        message.id(),
        message.partnerId(),
        message.billerCode(),
        message.messageEn(),
        message.messageBn(),
        message.validFrom() == null ? null : Timestamp.from(message.validFrom()),
        message.validUntil() == null ? null : Timestamp.from(message.validUntil()),
        message.enabled(),
        message.version());
  }

  public void deleteMessage(String id, int version) {
    jdbc.update("delete from maintenance_message where id = ? and version <= ?", id, version);
  }

  private static Long boxed(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private static void setLong(PreparedStatement ps, int index, Long value) throws SQLException {
    if (value == null) {
      ps.setObject(index, null);
    } else {
      ps.setLong(index, value);
    }
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp ts = rs.getTimestamp(column);
    return ts == null ? null : ts.toInstant();
  }

  private static List<String> texts(ResultSet rs, String column) throws SQLException {
    Array array = rs.getArray(column);
    if (array == null || array.getArray() == null) {
      return List.of();
    }
    return Arrays.asList((String[]) array.getArray());
  }
}

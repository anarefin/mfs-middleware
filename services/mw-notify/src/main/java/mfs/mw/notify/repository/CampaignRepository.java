package mfs.mw.notify.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code notify.campaign} and {@code notify.campaign_recipient} (ticket 111, NOT-07). */
@Repository
public class CampaignRepository {

  private final JdbcTemplate jdbc;

  public CampaignRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Inserts an approved campaign and its recipients. A repeat of the same id is ignored. */
  public boolean insertApproved(String id, String templateCode, String locale, String text, String segmentQuery,
      String customerType, Instant scheduleAt, int throttlePerSecond, long configVersion, List<String> msisdns) {
    int rows = jdbc.update(
        "insert into campaign (campaign_id, template_code, locale, body_text, segment_query, customer_type,"
            + " schedule_at, throttle_per_second, status, config_version) values (?, ?, ?, ?, ?, ?, ?, ?, 'APPROVED', ?)"
            + " on conflict (campaign_id) do nothing",
        id, templateCode, locale, text, segmentQuery, customerType, Timestamp.from(scheduleAt), throttlePerSecond,
        configVersion);
    if (rows == 0) {
      return false;
    }
    for (String msisdn : msisdns) {
      jdbc.update(
          "insert into campaign_recipient (campaign_id, msisdn) values (?, ?) on conflict do nothing", id, msisdn);
    }
    return true;
  }

  /** Approved campaigns whose schedule has passed become RUNNING. */
  public int startDue(Instant now) {
    return jdbc.update(
        "update campaign set status = 'RUNNING' where status = 'APPROVED' and schedule_at <= ?", Timestamp.from(now));
  }

  public List<Running> running() {
    return jdbc.query(
        "select campaign_id, template_code, locale, body_text, throttle_per_second from campaign where status = 'RUNNING'",
        (rs, i) -> new Running(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5)));
  }

  /** How many recipients were queued strictly after {@code since} (the open throttle window). */
  public int queuedSince(String campaignId, Instant since) {
    Integer n = jdbc.queryForObject(
        "select count(*) from campaign_recipient where campaign_id = ? and status = 'QUEUED' and sent_at > ?",
        Integer.class, campaignId, Timestamp.from(since));
    return n == null ? 0 : n;
  }

  /** The next pending recipients, locked, at most {@code limit}. */
  public List<String> lockPending(String campaignId, int limit) {
    return jdbc.query(
        "select msisdn from campaign_recipient where campaign_id = ? and status = 'PENDING'"
            + " order by msisdn limit ? for update skip locked",
        (rs, i) -> rs.getString(1), campaignId, limit);
  }

  public void mark(String campaignId, String msisdn, String status, Instant at) {
    jdbc.update(
        "update campaign_recipient set status = ?, sent_at = ? where campaign_id = ? and msisdn = ?",
        status, at == null ? null : Timestamp.from(at), campaignId, msisdn);
  }

  /** RUNNING with nothing left PENDING becomes DONE. */
  public void finishIfDrained(String campaignId) {
    jdbc.update(
        "update campaign set status = 'DONE' where campaign_id = ? and status = 'RUNNING'"
            + " and not exists (select 1 from campaign_recipient where campaign_id = ? and status = 'PENDING')",
        campaignId, campaignId);
  }

  public record Running(String id, String templateCode, String locale, String text, int throttlePerSecond) {}
}

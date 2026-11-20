package mfs.mw.adapter.interop.repository;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Table {@code npsb_inbound} (ticket 121b, security review): the permanent dedup of NPSB inbound
 * credits on (debtor participant, EndToEndId). Never purged, so a replay after any retention
 * window still credits once.
 */
@Repository
public class NpsbInboundRepository {

  private final JdbcTemplate jdbc;

  public NpsbInboundRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** True when this credit is new (row written), false when it was accepted before. */
  public boolean insertIfAbsent(String debtorAgent, String endToEndId, String txnId, Instant now) {
    return jdbc.update(
            "insert into npsb_inbound (debtor_agent, end_to_end_id, txn_id, received_at)"
                + " values (?, ?, ?, ?) on conflict do nothing",
            debtorAgent, endToEndId, txnId, Timestamp.from(now))
        == 1;
  }
}

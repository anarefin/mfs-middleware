package mfs.mw.adapter.atm.service;

import java.time.Clock;
import java.time.Duration;
import mfs.mw.adapter.atm.repository.PartnerPayloadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Retention of the raw payload store (ticket 97): rows of {@code partner_payload} older than
 * {@code mw.retention.partner-payload} (default 30 days) are deleted in batches of {@code
 * mw.retention.batch-size}. Run by {@code scheduler/PartnerPayloadPurgeJob}.
 */
@Service
public class PartnerPayloadRetentionService {

  private static final Logger log = LoggerFactory.getLogger(PartnerPayloadRetentionService.class);

  private final PartnerPayloadRepository payloads;
  private final Clock clock;
  private final Duration retention;
  private final int batchSize;

  public PartnerPayloadRetentionService(
      PartnerPayloadRepository payloads,
      Clock clock,
      @Value("${mw.retention.partner-payload:30d}") Duration retention,
      @Value("${mw.retention.batch-size:1000}") int batchSize) {
    this.payloads = payloads;
    this.clock = clock;
    this.retention = retention;
    this.batchSize = batchSize;
  }

  /** Purges the rows past retention and returns how many went. */
  public int purge() {
    int total = 0;
    int n;
    do {
      n = payloads.deleteBefore(clock.instant().minus(retention), batchSize);
      total += n;
    } while (n == batchSize);
    log.atInfo()
        .addKeyValue("event", "partner.payload.purged")
        .addKeyValue("rows", total)
        .log("purged partner payloads past retention");
    return total;
  }
}

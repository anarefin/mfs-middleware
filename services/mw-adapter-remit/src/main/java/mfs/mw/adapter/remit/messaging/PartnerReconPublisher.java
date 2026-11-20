package mfs.mw.adapter.remit.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import mfs.mw.adapter.remit.config.MwServiceProperties;
import mfs.mw.adapter.remit.dto.PartnerReconEvent;
import mfs.mw.adapter.remit.partner.config.PartnerProperties;
import mfs.mw.adapter.remit.partner.route.PartnerRecon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The partner framework's reconciliation sink (ticket 86): every line of a file read by a batch
 * route becomes a {@code mw.partner.recon.v1} record (key partnerId) through the outbox, all lines
 * of the file in one transaction, committed before the route ends, so mw-sftp-source moves the
 * file to {@code .done} only once the lines are stored, and a failure stores none. The 6.1 txn and
 * correlation header is a name UUID of {@code recon/<partnerId>/<fileId>} (a file has no
 * transaction), the idempotency key {@code recon/<partnerId>/<fileId>#<line>}. Logs counts only,
 * never a line.
 */
@Component
public class PartnerReconPublisher implements PartnerRecon.Sink {

  public static final String TOPIC = "mw.partner.recon.v1";

  private static final Logger log = LoggerFactory.getLogger(PartnerReconPublisher.class);

  private final OutboxWriter outbox;
  private final TransactionTemplate tx;
  private final MwServiceProperties service;
  private final PartnerProperties partners;
  private final Clock clock;

  public PartnerReconPublisher(
      OutboxWriter outbox, PlatformTransactionManager transactions, MwServiceProperties service,
      PartnerProperties partners, Clock clock) {
    this.outbox = outbox;
    this.tx = new TransactionTemplate(transactions);
    this.service = service;
    this.partners = partners;
    this.clock = clock;
  }

  @Override
  public void publish(PartnerRecon.File file, Stream<PartnerRecon.Line> lines) {
    String key = "recon/" + file.partnerId() + "/" + file.fileId();
    String id = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    Instant received = clock.instant();
    int written = tx.execute(s -> {
      int[] n = {0};
      lines.forEach(line -> {
        outbox.append(TOPIC, file.partnerId(), new EventMetadata(id, id, key + "#" + line.lineNumber(), 1),
            new PartnerReconEvent(1, file.partnerId(), partners.family(), file.fileName(), file.fileId(), line.lineNumber(),
                line.last(), line.fields(), service.name(), received));
        n[0]++;
      });
      return n[0];
    });
    log.atInfo()
        .addKeyValue("event", "partner.recon.file_published")
        .addKeyValue("partnerId", file.partnerId())
        .addKeyValue("lines", written)
        .log("partner reconciliation file published");
  }
}

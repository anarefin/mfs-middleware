package mfs.mw.admin.api.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mfs.mw.admin.api.config.CredentialProperties;
import mfs.mw.admin.api.dto.NotifyRequest;
import mfs.mw.admin.api.entity.CredentialMeta;
import mfs.mw.admin.api.messaging.EventMetadata;
import mfs.mw.admin.api.messaging.OutboxWriter;
import mfs.mw.admin.api.repository.CredentialMetaRepository;
import mfs.mw.admin.api.util.CorrelationIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Credential expiry alerts (ticket 131, FRS ADM-02): a credential with at most {@code t} days left
 * (thresholds 30, 7, 1; expired ones fall under the smallest) raises one alert for the smallest
 * threshold it is under, once per credential version and threshold ({@code credential_alert}; the
 * larger thresholds it skipped are marked too, so a credential first seen at 5 days alerts once for
 * 7, not for 30 as well). The alert is a WARN log event {@code credential.expiring} and an ops email
 * on {@code mw.notify.request.v1} (template {@code OPS_CREDENTIAL_EXPIRING}, seeded bn/en in
 * mw-notify), written to the outbox in the same transaction as the ledger row.
 */
@Service
public class CredentialExpiryService {

  public static final String NOTIFY_TOPIC = "mw.notify.request.v1";
  public static final String TEMPLATE = "OPS_CREDENTIAL_EXPIRING";

  private static final Logger log = LoggerFactory.getLogger(CredentialExpiryService.class);
  private static final long DAY = 86_400L;

  private final CredentialMetaRepository metas;
  private final OutboxWriter outbox;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final CredentialProperties.Expiry props;

  public CredentialExpiryService(
      CredentialMetaRepository metas, OutboxWriter outbox, TransactionTemplate tx, Clock clock, CredentialProperties props) {
    this.metas = metas;
    this.outbox = outbox;
    this.tx = tx;
    this.clock = clock;
    this.props = props.expiry();
  }

  /** Raises every alert now due; returns how many. */
  public int raiseDue() {
    Instant now = clock.instant();
    List<Integer> thresholds = props.thresholdsDays().stream().sorted(Comparator.reverseOrder()).toList();
    int n = 0;
    for (CredentialMeta m : metas.expiringBy(now.plusSeconds(thresholds.getFirst() * DAY))) {
      Optional<Integer> due = due(m.notAfter(), now, thresholds);
      if (due.isPresent() && Boolean.TRUE.equals(tx.execute(s -> raise(m, due.get(), thresholds, now)))) {
        n++;
      }
    }
    return n;
  }

  /** The smallest threshold {@code notAfter} is within, empty when it is within none. */
  static Optional<Integer> due(Instant notAfter, Instant now, List<Integer> thresholds) {
    long left = Duration.between(now, notAfter).toSeconds();
    return thresholds.stream().filter(t -> left <= t * DAY).min(Comparator.naturalOrder());
  }

  private boolean raise(CredentialMeta m, int threshold, List<Integer> thresholds, Instant now) {
    if (!metas.markAlerted(m.credentialId(), m.version(), threshold, now)) {
      return false;
    }
    thresholds.stream().filter(t -> t > threshold).forEach(t -> metas.markAlerted(m.credentialId(), m.version(), t, now));
    long daysLeft = Math.floorDiv(Duration.between(now, m.notAfter()).toSeconds(), DAY);
    String messageId = "credential-expiry:" + m.credentialId() + ":v" + m.version() + ":" + threshold + "d";
    String correlationId = CorrelationIds.current();
    Map<String, String> params = new LinkedHashMap<>();
    params.put("partnerId", m.partnerId());
    params.put("credential", m.credentialId());
    params.put("type", m.type().name());
    params.put("kid", m.kid());
    params.put("notAfter", m.notAfter().toString());
    params.put("daysLeft", Long.toString(daysLeft));
    params.put("threshold", Integer.toString(threshold));
    NotifyRequest email = new NotifyRequest(1, messageId, correlationId, new NotifyRequest.Recipient(props.opsEmail()),
        TEMPLATE, props.locale(), params, List.of("EMAIL"), "INFO");
    outbox.append(NOTIFY_TOPIC, props.opsEmail(), new EventMetadata(correlationId, messageId, messageId, 1), email);
    log.atWarn()
        .addKeyValue("event", "credential.expiring")
        .addKeyValue("partnerId", m.partnerId())
        .addKeyValue("resourceId", m.credentialId())
        .addKeyValue("thresholdDays", threshold)
        .addKeyValue("daysLeft", daysLeft)
        .log("partner credential expires soon");
    return true;
  }
}

package mfs.mw.checkout.messaging;

import mfs.mw.checkout.config.CheckoutProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Keeps the checkout OTP out of the outbox once it is relayed (review A8). The OTP SMS request
 * ({@code mw.notify.request.v1}, {@code params.otp}) must go through the outbox to commit with the
 * session change (spec 6.4), but the sent row would otherwise keep the code in clear until the
 * 7 day purge. This removes {@code params.otp} from every relayed notify row (sent_at set), in
 * one statement; unsent rows keep it for the relay. Beside {@link OutboxRelay} on purpose (the
 * relay is being reworked separately); {@code scheduler/OutboxOtpScrubJob} runs it every few
 * seconds. The partial index {@code outbox_otp_unscrubbed_idx} (V6) keeps the scan to the rows
 * that still carry an OTP.
 */
@Component
public class OutboxOtpScrubber {

  private static final Logger log = LoggerFactory.getLogger(OutboxOtpScrubber.class);

  private final JdbcTemplate jdbc;
  private final String notifyTopic;

  public OutboxOtpScrubber(JdbcTemplate jdbc, CheckoutProperties props) {
    this.jdbc = jdbc;
    this.notifyTopic = props.notifyTopic();
  }

  /** Removes the OTP from relayed notify rows; the number of rows changed. */
  public int scrubRelayed() {
    int n = jdbc.update(
        "update outbox set payload = payload #- '{params,otp}'"
            + " where payload->'params' ?? 'otp' and sent_at is not null and topic = ?",
        notifyTopic);
    if (n > 0) {
      log.atDebug().addKeyValue("event", "outbox.otp.scrubbed").log("OTP removed from {} relayed outbox rows", n);
    }
    return n;
  }
}

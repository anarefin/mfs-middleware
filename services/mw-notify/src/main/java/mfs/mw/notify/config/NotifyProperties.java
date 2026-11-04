package mfs.mw.notify.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Notification behaviour (spec 10).
 *
 * @param retryBackoff delays before each retry of a transient SMPP error (10.4: 2 s, 10 s, 30 s);
 *     their count is the number of retries before the message fails
 * @param dispatchBatch messages per priority the dispatcher takes per cycle
 * @param defaultSender source address when the template names no sender ID
 * @param defaultLocale locale of rule-built messages when the event carries none
 * @param receiptBufferTtl how long an unmatched DLR waits for its segment
 */
@Validated
@ConfigurationProperties("mw.notify")
public record NotifyProperties(
    @NotEmpty List<Duration> retryBackoff,
    @Positive int dispatchBatch,
    @NotBlank String defaultSender,
    @NotBlank String defaultLocale,
    Duration receiptBufferTtl) {

  public NotifyProperties {
    retryBackoff = List.copyOf(retryBackoff);
    receiptBufferTtl = receiptBufferTtl == null ? Duration.ofDays(1) : receiptBufferTtl;
  }

  public int retries() {
    return retryBackoff.size();
  }
}

package mfs.mw.notify.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
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
 * @param quietHours when PROMO messages are held (spec 10.1)
 */
@Validated
@ConfigurationProperties("mw.notify")
public record NotifyProperties(
    @NotEmpty List<Duration> retryBackoff,
    @Positive int dispatchBatch,
    @NotBlank String defaultSender,
    @NotBlank String defaultLocale,
    Duration receiptBufferTtl,
    QuietHours quietHours) {

  public NotifyProperties {
    retryBackoff = List.copyOf(retryBackoff);
    receiptBufferTtl = receiptBufferTtl == null ? Duration.ofDays(1) : receiptBufferTtl;
    quietHours = quietHours == null ? QuietHours.DEFAULT : quietHours;
  }

  /**
   * PROMO quiet hours (spec 10.1): from {@code start} (inclusive) to {@code end} (exclusive) local
   * time in {@code zone}; a window whose end is not after its start spans midnight. Default
   * 22:00-08:00 Asia/Dhaka, on.
   */
  public record QuietHours(Boolean enabled, LocalTime start, LocalTime end, ZoneId zone) {

    public static final QuietHours DEFAULT =
        new QuietHours(true, LocalTime.of(22, 0), LocalTime.of(8, 0), ZoneId.of("Asia/Dhaka"));

    public QuietHours {
      enabled = enabled == null || enabled;
      start = start == null ? LocalTime.of(22, 0) : start;
      end = end == null ? LocalTime.of(8, 0) : end;
      zone = zone == null ? ZoneId.of("Asia/Dhaka") : zone;
    }

    /** Whether {@code now} falls in the quiet window. An empty window (start = end) is never quiet. */
    public boolean contains(Instant now) {
      if (!enabled || start.equals(end)) {
        return false;
      }
      LocalTime t = now.atZone(zone).toLocalTime();
      return start.isBefore(end)
          ? !t.isBefore(start) && t.isBefore(end)
          : !t.isBefore(start) || t.isBefore(end);
    }
  }

  public int retries() {
    return retryBackoff.size();
  }
}

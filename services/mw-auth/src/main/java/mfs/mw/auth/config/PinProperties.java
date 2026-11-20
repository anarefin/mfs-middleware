package mfs.mw.auth.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.auth.pin.*}: the PIN lifecycle (spec 18.7, D20; ticket 70).
 *
 * @param history the last N PINs (the current one included) a new PIN may not repeat
 * @param lock the lockout levels
 * @param reset PIN reset lifetimes
 * @param auditTopic where audit events go ({@code mw.audit.event.v1})
 */
@Validated
@ConfigurationProperties("mw.auth.pin")
public record PinProperties(
    @Min(1) int history,
    @Valid @NotNull Lock lock,
    @Valid @NotNull Reset reset,
    @NotBlank String auditTopic) {

  /**
   * @param tries wrong PINs per level before the next lock
   * @param level1 how long the first lock lasts
   * @param level2 how long the second lock lasts; the third is until a reset
   * @param decay the level history is forgotten this long after the last lock
   */
  public record Lock(@Min(1) int tries, @NotNull Duration level1, @NotNull Duration level2, @NotNull Duration decay) {

    /** How long a lock of {@code level} lasts; null = until a reset (level 3 and up). */
    public Duration duration(int level) {
      return switch (level) {
        case 1 -> level1;
        case 2 -> level2;
        default -> null;
      };
    }
  }

  /**
   * @param ttl a self-service reset (and a care reset after its OTP) lives this long
   * @param careTtl an approved care reset waits this long for the customer's OTP
   */
  public record Reset(@NotNull Duration ttl, @NotNull Duration careTtl) {}
}

package mfs.mw.orchestrator.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.Optional;
import mfs.mw.orchestrator.service.saga.Status;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Saga settings under {@code mw.orchestrator} (spec 7.2): the deadline of each waiting state,
 * the lifetime of a core command, the idempotency TTL (7.3) and the timer sweep (7.1).
 *
 * @param validatingTimeout how long VALIDATING waits for the core before FAILED
 * @param holdingTimeout HOLDING wait before the core is asked what it holds
 * @param postingTimeout POSTING wait before UNKNOWN
 * @param partnerTimeout PARTNER_PENDING wait before UNKNOWN (per partner override later)
 * @param retryTimeout COMMITTING, RELEASING and REVERSING resend interval
 * @param commandTtl {@code expiresAt - issuedAt} of a core command
 * @param idempotencyTtl how long an idempotency key is remembered
 * @param walletCommandTopic where wallet commands go
 * @param timer the timer sweep
 */
@Validated
@ConfigurationProperties("mw.orchestrator")
public record OrchestratorProperties(
    @NotNull @DefaultValue("5s") Duration validatingTimeout,
    @NotNull @DefaultValue("5s") Duration holdingTimeout,
    @NotNull @DefaultValue("5s") Duration postingTimeout,
    @NotNull @DefaultValue("30s") Duration partnerTimeout,
    @NotNull @DefaultValue("5s") Duration retryTimeout,
    @NotNull @DefaultValue("60s") Duration commandTtl,
    @NotNull @DefaultValue("72h") Duration idempotencyTtl,
    @NotBlank @DefaultValue("core.wallet.command.v1") String walletCommandTopic,
    @NotNull @DefaultValue Timer timer) {

  /**
   * @param enabled runs the sweep job (tests switch it off and call the service)
   * @param period delay between sweeps
   * @param batchSize timers locked per sweep
   */
  public record Timer(
      @DefaultValue("true") boolean enabled,
      @NotNull @DefaultValue("1s") Duration period,
      @Positive @DefaultValue("50") int batchSize) {}

  /**
   * The deadline armed when a transaction enters {@code status}. UNKNOWN has none yet: its
   * status-inquiry schedule (7.4) is a later ticket, as are the terminal states and MANUAL_REVIEW.
   */
  public Optional<Duration> deadline(Status status) {
    return switch (status) {
      case RECEIVED -> Optional.empty();
      case VALIDATING -> Optional.of(validatingTimeout);
      case HOLDING -> Optional.of(holdingTimeout);
      case POSTING -> Optional.of(postingTimeout);
      case PARTNER_PENDING -> Optional.of(partnerTimeout);
      case COMMITTING, RELEASING, REVERSING -> Optional.of(retryTimeout);
      case UNKNOWN, MANUAL_REVIEW, COMPLETED, FAILED, REJECTED, REVERSED -> Optional.empty();
    };
  }
}

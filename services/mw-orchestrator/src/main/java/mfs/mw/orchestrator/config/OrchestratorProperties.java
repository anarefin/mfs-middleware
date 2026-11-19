package mfs.mw.orchestrator.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
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
 * @param timer the timer sweep
 * @param reversingTimeout how long REVERSING resends ReverseTransaction before MANUAL_REVIEW
 * @param reversalChannels the channels that may send a REVERSAL request (operations: SYSTEM)
 * @param bulkChannels the channels that may send a BULK_DISBURSE child (the org portal, ticket 34)
 * @param idempotencyLock the Redis double-tap lock in front of the idempotency claim (7.3 rule 4)
 * @param duplicateGuard the business-duplicate window (FRS PLT-013, ticket 99)
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
    @NotNull @DefaultValue Timer timer,
    @NotNull @DefaultValue("60s") Duration reversingTimeout,
    @NotNull @DefaultValue("SYSTEM") Set<String> reversalChannels,
    @NotNull @DefaultValue("ORG_PORTAL") Set<String> bulkChannels,
    @NotNull @DefaultValue IdempotencyLock idempotencyLock,
    @NotNull @DefaultValue DuplicateGuard duplicateGuard) {

  /**
   * The business-duplicate window (FRS PLT-013): the same initiator, txnType, parties and amount
   * under another idempotency key within {@code window}.
   *
   * @param enabled asks Redis (off together with the lock when there is none: the lock
   *     client answers {@code Off})
   * @param window how long a tuple counts as a possible duplicate
   * @param promptChannels channels that get the confirmable prompt {@code MW-VAL-024} and may resend
   *     with {@code confirmDuplicate}; every other channel (API clients) is refused {@code MW-VAL-025}
   * @param exemptChannels channels never checked (operations)
   */
  public record DuplicateGuard(
      @DefaultValue("true") boolean enabled,
      @NotNull @DefaultValue("60s") Duration window,
      @NotNull @DefaultValue({"CUSTOMER_APP", "AGENT_APP", "MERCHANT_APP", "DISTRIBUTOR_APP", "DSO_APP", "USSD"})
          Set<String> promptChannels,
      @NotNull @DefaultValue("SYSTEM") Set<String> exemptChannels) {}

  /**
   * @param enabled asks Redis (tests without Redis switch it off; the primary key alone then)
   * @param ttl how long the lock absorbs double-taps
   */
  public record IdempotencyLock(
      @DefaultValue("true") boolean enabled, @NotNull @DefaultValue("60s") Duration ttl) {}

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

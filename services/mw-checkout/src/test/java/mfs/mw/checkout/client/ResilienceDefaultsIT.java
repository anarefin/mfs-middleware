package mfs.mw.checkout.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.time.Duration;
import mfs.mw.checkout.TestInfrastructure;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Resilience4j is wired with the spec 19.2 defaults, which every partner instance inherits unless
 * it overrides them ({@code resilience4j.*.instances.<partner>}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class ResilienceDefaultsIT {

  private final CircuitBreakerRegistry circuitBreakers;
  private final BulkheadRegistry bulkheads;
  private final RetryRegistry retries;
  private final TimeLimiterRegistry timeLimiters;

  @Autowired
  ResilienceDefaultsIT(CircuitBreakerRegistry circuitBreakers, BulkheadRegistry bulkheads, RetryRegistry retries, TimeLimiterRegistry timeLimiters) {
    this.circuitBreakers = circuitBreakers;
    this.bulkheads = bulkheads;
    this.retries = retries;
    this.timeLimiters = timeLimiters;
  }

  @Test
  void circuitBreakerOpensAtHalfFailedOrSlowCallsOverSixtySeconds() {
    CircuitBreakerConfig cb = circuitBreakers.circuitBreaker("any-partner").getCircuitBreakerConfig();

    assertThat(cb.getSlidingWindowType()).isEqualTo(SlidingWindowType.TIME_BASED);
    assertThat(cb.getSlidingWindowSize()).isEqualTo(60);
    assertThat(cb.getMinimumNumberOfCalls()).isEqualTo(50);
    assertThat(cb.getFailureRateThreshold()).isEqualTo(50f);
    assertThat(cb.getSlowCallRateThreshold()).isEqualTo(50f);
    assertThat(cb.getSlowCallDurationThreshold()).isEqualTo(Duration.ofSeconds(24));
    assertThat(cb.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(30_000L);
    assertThat(cb.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(5);
  }

  @Test
  void bulkheadAllowsTwoHundredConcurrentCalls() {
    BulkheadConfig bulkhead = bulkheads.bulkhead("any-partner").getBulkheadConfig();

    assertThat(bulkhead.getMaxConcurrentCalls()).isEqualTo(200);
  }

  @Test
  void timeLimiterDefaultsToThirtySeconds() {
    assertThat(timeLimiters.timeLimiter("any-partner").getTimeLimiterConfig().getTimeoutDuration())
        .isEqualTo(Duration.ofSeconds(30));
  }

  @RepeatedTest(20)
  void retryMakesTwoRetriesWithExponentialJitteredBackoffUpToOneSecond() {
    RetryConfig retry = retries.retry("any-partner").getRetryConfig();

    assertThat(retry.getMaxAttempts()).isEqualTo(3);
    long first = retry.getIntervalBiFunction().apply(1, null);
    long second = retry.getIntervalBiFunction().apply(2, null);
    assertThat(first).isBetween(100L, 300L);
    assertThat(second).isBetween(200L, 1_000L);
  }
}

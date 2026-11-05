package mfs.mw.app.gateway.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import mfs.mw.app.gateway.TestInfrastructure;
import mfs.mw.app.gateway.entity.DeviceOtp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code app_device_otp} against PostgreSQL (the module's JVM-scoped {@link TestInfrastructure}):
 * sends counted per wallet and window, every check reserved atomically before it is compared (as
 * mw-checkout's ticket 31 fix), an exhausted, expired, spent or other device's OTP never consumed.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"mw.app-gateway.consumers.enabled=false", "mw.outbox.relay.enabled=false"})
@Import(TestInfrastructure.class)
class DeviceOtpRepositoryIT {

  static final Instant NOW = Instant.parse("2026-11-05T08:00:00Z");
  static final String WALLET = "01711000077";
  static final String DEVICE = "dev-otp-00000001";
  static final Duration WINDOW = Duration.ofMinutes(15);

  private final DeviceOtpRepository otps;
  private final JdbcTemplate jdbc;

  @Autowired
  DeviceOtpRepositoryIT(DeviceOtpRepository otps, JdbcTemplate jdbc) {
    this.otps = otps;
    this.jdbc = jdbc;
  }

  @BeforeEach
  void clean() {
    jdbc.update("delete from app_device_otp");
  }

  private int issue(String hash, Instant at) {
    return otps.issue(WALLET, DEVICE, hash, "en", at.plusSeconds(120), 3, WINDOW, at).orElse(-1);
  }

  @Test
  void aNewOtpReplacesTheOldOneAndResetsItsChecks() {
    assertThat(issue("h1", NOW)).isEqualTo(1);
    assertThat(otps.reserveAttempt(WALLET, DEVICE, "h1", 3, NOW)).isTrue();
    assertThat(issue("h2", NOW.plusSeconds(10))).isEqualTo(2);

    DeviceOtp o = otps.find(WALLET).orElseThrow();
    assertThat(o.otpHash()).isEqualTo("h2");
    assertThat(o.attempts()).isZero();
    assertThat(o.locale()).isEqualTo("en");
    assertThat(otps.reserveAttempt(WALLET, DEVICE, "h1", 3, NOW)).as("the replaced OTP").isFalse();
  }

  @Test
  void sendsAreLimitedPerWindowAndCountAgainAfterIt() {
    assertThat(issue("h1", NOW)).isEqualTo(1);
    assertThat(issue("h2", NOW.plusSeconds(1))).isEqualTo(2);
    assertThat(issue("h3", NOW.plusSeconds(2))).isEqualTo(3);
    assertThat(issue("h4", NOW.plusSeconds(3))).as("over the limit").isEqualTo(-1);
    assertThat(otps.find(WALLET).orElseThrow().otpHash()).as("the last one stays").isEqualTo("h3");

    assertThat(issue("h5", NOW.plus(WINDOW))).as("a new window").isEqualTo(1);
  }

  @Test
  void concurrentChecksReserveNoMoreThanTheMaximum() throws Exception {
    issue("h1", NOW);
    int threads = 24;
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger reserved = new AtomicInteger();
    try (var pool = Executors.newFixedThreadPool(threads)) {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        futures.add(pool.submit(() -> {
          start.await();
          if (otps.reserveAttempt(WALLET, DEVICE, "h1", 3, NOW)) {
            reserved.incrementAndGet();
          }
          return null;
        }));
      }
      start.countDown();
      for (Future<?> f : futures) {
        f.get();
      }
    }
    assertThat(reserved.get()).as("OTP checks allowed").isEqualTo(3);
    assertThat(otps.find(WALLET).orElseThrow().attempts()).isEqualTo(3);

    otps.voidIfExhausted(WALLET, "h1", 3, NOW);
    assertThat(otps.find(WALLET).orElseThrow().otpHash()).isNull();
    assertThat(otps.consume(WALLET, DEVICE, "h1", 3, NOW)).isFalse();
  }

  @Test
  void anOtpIsConsumedOnceOnlyForItsDeviceAndBeforeItExpires() {
    issue("h1", NOW);
    otps.reserveAttempt(WALLET, DEVICE, "h1", 3, NOW);
    assertThat(otps.consume(WALLET, "dev-otp-other0001", "h1", 3, NOW)).as("another device").isFalse();
    assertThat(otps.consume(WALLET, DEVICE, "h1", 3, NOW.plusSeconds(120))).as("expired").isFalse();
    assertThat(otps.reserveAttempt(WALLET, DEVICE, "h1", 3, NOW.plusSeconds(120))).as("expired").isFalse();
    assertThat(otps.consume(WALLET, DEVICE, "h1", 3, NOW)).isTrue();
    assertThat(otps.consume(WALLET, DEVICE, "h1", 3, NOW)).as("spent").isFalse();
  }

  @Test
  void aConsumeNeedsACheckWithinTheMaximum() {
    issue("h1", NOW);
    for (int i = 0; i < 3; i++) {
      otps.reserveAttempt(WALLET, DEVICE, "h1", 3, NOW);
    }
    assertThat(otps.consume(WALLET, DEVICE, "h1", 2, NOW)).isFalse();
    assertThat(otps.consume(WALLET, DEVICE, "h1", 3, NOW)).as("the third check may be the right one").isTrue();
  }
}

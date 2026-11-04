package mfs.mw.portal.api.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** txnId is a UUIDv7 as the contract's TxnId pattern requires, ordered by time (spec 5.1). */
class UuidV7Test {

  static final String PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

  @Test
  void versionVariantAndTimestamp() {
    Instant at = Instant.parse("2026-11-04T06:00:00.123Z");
    UUID id = UuidV7.next(Clock.fixed(at, ZoneOffset.UTC));
    assertThat(id.toString()).matches(PATTERN);
    assertThat(id.version()).isEqualTo(7);
    assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(at.toEpochMilli());
  }

  @Test
  void laterMillisSortLater() {
    UUID a = UuidV7.next(Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC));
    UUID b = UuidV7.next(Clock.fixed(Instant.ofEpochMilli(1_001), ZoneOffset.UTC));
    assertThat(a.toString()).isLessThan(b.toString());
  }
}

package mfs.mw.notify.dto;

import java.time.Duration;

/**
 * Message priority (spec 10.1), highest first: OTP > TXN > INFO > PROMO. Each has its own SMPP
 * bind pool and is served before the next by the dispatcher. {@link #defaultTtl()} is the {@code
 * expiresAt} default when a request carries none (OTP 2 min, TXN 24 h; INFO 24 h and PROMO 72 h
 * are this service's choice).
 */
public enum Priority {
  OTP(Duration.ofMinutes(2)),
  TXN(Duration.ofHours(24)),
  INFO(Duration.ofHours(24)),
  PROMO(Duration.ofHours(72));

  private final Duration defaultTtl;

  Priority(Duration defaultTtl) {
    this.defaultTtl = defaultTtl;
  }

  public Duration defaultTtl() {
    return defaultTtl;
  }

  /** 0 for OTP up to 3 for PROMO: the dispatcher serves lower ranks first. */
  public int rank() {
    return ordinal();
  }
}

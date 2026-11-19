package mfs.mw.core.mock.entity;

/**
 * A ledger wallet row (own schema only). Balances are minor units. The core holds no PIN (ticket 72).
 * {@code holderName} is the display name of the holder (null for system wallets). Types, statuses and
 * KYC levels of FRS 7.1 (ticket 94).
 */
public record Wallet(
    String id,
    String msisdn,
    String type,
    String status,
    long balanceMinor,
    long heldMinor,
    String holderName,
    String kycLevel) {

  /**
   * Locked by the core for its own reasons (a real core: compliance, fraud; ticket 72 removed the
   * PIN lockout, which mw-auth now owns): cannot pay, can still receive; lifted by {@code UnlockWallet}.
   */
  public static final String LOCKED = "LOCKED";

  /** Not used for long (FRS 7.1): cannot pay, can still receive (ticket 94). */
  public static final String DORMANT = "DORMANT";

  /** Closed for good (FRS 7.1): neither pays nor receives (ticket 94). */
  public static final String CLOSED = "CLOSED";

  public static final String ORGANISATION = "ORGANISATION";

  /**
   * The stored wallet type of {@code type}: {@code ORG}, the name before ticket 94 (still sent by
   * mw-seeder and accepted in config), is {@code ORGANISATION}; any other type as it is.
   */
  public static String canonicalType(String type) {
    return "ORG".equals(type) ? ORGANISATION : type;
  }

  public boolean active() {
    return "ACTIVE".equals(status);
  }

  public boolean locked() {
    return LOCKED.equals(status);
  }

  public boolean dormant() {
    return DORMANT.equals(status);
  }

  public boolean closed() {
    return CLOSED.equals(status);
  }

  /** May be credited: active, locked or dormant (never blocked, on hold or closed). */
  public boolean canReceive() {
    return active() || locked() || dormant();
  }

  /** Funds that can be spent: balance minus what a hold reserves. */
  public long availableMinor() {
    return balanceMinor - heldMinor;
  }
}

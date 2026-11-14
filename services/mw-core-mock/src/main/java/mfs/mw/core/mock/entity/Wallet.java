package mfs.mw.core.mock.entity;

/** A ledger wallet row (own schema only). Balances are minor units. The core holds no PIN (ticket 72). */
public record Wallet(String id, String msisdn, String type, String status, long balanceMinor, long heldMinor) {

  /**
   * Locked by the core for its own reasons (a real core: compliance, fraud, dormancy; ticket 72
   * removed the PIN lockout, which mw-auth now owns): cannot pay, can still receive; lifted by
   * {@code UnlockWallet}.
   */
  public static final String LOCKED = "LOCKED";

  public boolean active() {
    return "ACTIVE".equals(status);
  }

  public boolean locked() {
    return LOCKED.equals(status);
  }

  /** May be credited: active, or only locked. */
  public boolean canReceive() {
    return active() || locked();
  }

  /** Funds that can be spent: balance minus what a hold reserves. */
  public long availableMinor() {
    return balanceMinor - heldMinor;
  }
}

package mfs.mw.core.mock.entity;

/** A ledger wallet row (own schema only). Balances are minor units. */
public record Wallet(
    String id, String msisdn, String type, String status, long balanceMinor, long heldMinor, String pinHash) {

  /** Locked after too many consecutive wrong PINs: cannot pay, can still receive. */
  public static final String LOCKED = "LOCKED";

  public boolean active() {
    return "ACTIVE".equals(status);
  }

  public boolean locked() {
    return LOCKED.equals(status);
  }

  /** May be credited: active, or only PIN-locked. */
  public boolean canReceive() {
    return active() || locked();
  }

  /** Funds that can be spent: balance minus what a hold reserves. */
  public long availableMinor() {
    return balanceMinor - heldMinor;
  }
}

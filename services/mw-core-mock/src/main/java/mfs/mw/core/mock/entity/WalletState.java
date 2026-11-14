package mfs.mw.core.mock.entity;

/**
 * What {@code WalletStateChanged} publishes of a wallet (ticket 67): identity, type, status, whether
 * it has a PIN and its state version. Read for wallets with an MSISDN only (people log in to them).
 */
public record WalletState(String id, String msisdn, String type, String status, boolean hasPin, long version) {}

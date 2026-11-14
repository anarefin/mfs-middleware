package mfs.mw.core.mock.entity;

/**
 * What {@code WalletStateChanged} publishes of a wallet (ticket 67): identity, type, status and its
 * state version. Read for wallets with an MSISDN only (people log in to them).
 */
public record WalletState(String id, String msisdn, String type, String status, long version) {}

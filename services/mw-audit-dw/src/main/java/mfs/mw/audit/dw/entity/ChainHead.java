package mfs.mw.audit.dw.entity;

/**
 * Row of {@code audit.chain_head}: the last sequence number and hash of one monthly chain. It is
 * the anchor that makes a deleted last row detectable (spec 14.3; the daily WORM export of the
 * anchor is TBC).
 */
public record ChainHead(String month, long seq, byte[] hash) {}

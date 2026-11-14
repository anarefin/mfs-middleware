package mfs.mw.audit.dw.entity;

import java.time.LocalDate;

/**
 * Row of {@code audit.anchor} (ticket 74): one signed daily anchor. {@code jws} is what is
 * verified; {@code anchorHash} is the SHA-256 of it as stored. Never leaves the service layer.
 */
public record AnchorRow(LocalDate date, String jws, String kid, byte[] anchorHash) {}

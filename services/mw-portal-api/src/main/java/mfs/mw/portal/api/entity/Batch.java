package mfs.mw.portal.api.entity;

import java.time.Instant;
import java.util.UUID;

/** Row of {@code bulk.batch} (own schema only); never leaves the service layer. */
public record Batch(
    UUID batchId,
    String orgId,
    BatchStatus status,
    int rowCount,
    long totalMinor,
    String currency,
    String fileSha256,
    String makerSub,
    String makerName,
    String checkerSub,
    String checkerName,
    String reason,
    Instant createdAt,
    Instant decidedAt) {}

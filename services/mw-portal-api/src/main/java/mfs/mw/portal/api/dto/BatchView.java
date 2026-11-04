package mfs.mw.portal.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * A bulk batch as the portal sees it (contract {@code api/mw-portal-api/portal-org.v1.openapi.yaml}).
 * {@code parentTxnId} and {@code progress} appear once approved, {@code completedAt} once every row
 * settled (ticket 34).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BatchView(
    String batchId,
    String orgId,
    String status,
    int rowCount,
    Amount totalAmount,
    String makerId,
    String checkerId,
    String reason,
    Instant createdAt,
    Instant decidedAt,
    String parentTxnId,
    BatchProgress progress,
    Instant completedAt) {}

package mfs.mw.orchestrator.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Body of the test-only {@code POST /test/v1/reversals} (ticket 58): the COMPLETED transaction to
 * reverse and an optional reason.
 */
public record TestReversalRequest(@NotNull UUID originalTxnId, @Size(max = 200) String reason) {}

package mfs.mw.orchestrator.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Body of the test-only {@code POST /test/v1/inquiries} (ticket 112c): the transaction to ask about now. */
public record InquiryRequest(@NotNull UUID txnId) {}

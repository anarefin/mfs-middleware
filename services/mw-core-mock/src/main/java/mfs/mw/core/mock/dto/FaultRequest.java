package mfs.mw.core.mock.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import mfs.mw.core.mock.dto.Fault.FaultMode;

/** Body of {@code POST /core-mock/v1/faults}; see {@link Fault} for the fields. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FaultRequest(
    @NotNull FaultMode mode,
    String commandType,
    String txnId,
    @Min(1) Long delayMs,
    String reasonCode,
    @Min(1) Integer times) {}

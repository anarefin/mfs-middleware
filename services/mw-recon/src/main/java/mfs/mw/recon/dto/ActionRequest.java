package mfs.mw.recon.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/**
 * An action a checker approved in mw-admin-api (ticket 127b, change request type {@code RECON_EXCEPTION}). mw-admin-api
 * has already sent it to mw-orchestrator; mw-recon only records it and waits for the outcome.
 */
public record ActionRequest(
    @NotNull @Pattern(regexp = "FORCE_COMMIT|REVERSE") String action, @NotNull UUID changeRequestId) {}

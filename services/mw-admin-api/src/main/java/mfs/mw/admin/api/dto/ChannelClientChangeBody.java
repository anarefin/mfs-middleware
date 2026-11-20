package mfs.mw.admin.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /admin/v1/channel-clients/{clientId}/change-requests} (ticket 100a): register or
 * replace a channel client with its full definition. Checked against the contract first ({@code
 * controller/BodySchemaAdvice}); {@code reason} is free text: never logged, scrubbed in the audit.
 */
public record ChannelClientChangeBody(
    @NotNull ChannelClientAction action, @NotBlank @Size(max = 500) String reason, @NotNull @Valid ChannelClientSpec client) {

  /** The registry changes the console offers. */
  public enum ChannelClientAction {
    UPSERT
  }
}

package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Body of {@code POST /app/v1/add-money/card/sessions} (ticket 118b, contract {@code CardSessionRequest}):
 * the amount and the PIN. No card field: the card is typed on the acquirer's hosted page only (FL-07);
 * the body schema refuses any other property before this record is read.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CardSessionRequest(
    @NotNull @Valid Amount amount,
    @NotNull @Valid EncryptedPin encryptedPin,
    @Pattern(regexp = "bn|en") String locale) {}

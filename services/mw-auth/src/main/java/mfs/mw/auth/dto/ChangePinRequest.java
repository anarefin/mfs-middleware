package mfs.mw.auth.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Body of {@code PUT /auth/v1/pin} (ticket 70): the current PIN (step-up) and the new one. */
public record ChangePinRequest(@NotNull @Valid EncryptedPin encryptedPin, @NotNull @Valid EncryptedPin newEncryptedPin) {}

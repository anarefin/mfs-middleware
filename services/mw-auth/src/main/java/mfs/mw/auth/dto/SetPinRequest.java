package mfs.mw.auth.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Body of {@code POST /auth/v1/pin}: the first PIN. */
public record SetPinRequest(@NotNull @Valid EncryptedPin encryptedPin) {}

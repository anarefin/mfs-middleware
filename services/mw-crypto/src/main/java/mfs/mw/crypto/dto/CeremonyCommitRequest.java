package mfs.mw.crypto.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * {@code POST /internal/v1/ceremonies/{ceremonyId}/commit} (ticket 75): the KEY_CEREMONY change
 * request was approved by {@code checkerId}, who is not its maker.
 */
public record CeremonyCommitRequest(
    @NotBlank @Pattern(regexp = "[A-Za-z0-9._@-]{1,128}") String makerId,
    @NotBlank @Pattern(regexp = "[A-Za-z0-9._@-]{1,128}") String checkerId) {}

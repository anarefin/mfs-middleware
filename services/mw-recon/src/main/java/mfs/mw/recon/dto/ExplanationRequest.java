package mfs.mw.recon.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** An operator's explanation of an exception (FRS 10.4 #7: every difference is resolved or explained). */
public record ExplanationRequest(@NotBlank @Size(max = 2000) String explanation) {}

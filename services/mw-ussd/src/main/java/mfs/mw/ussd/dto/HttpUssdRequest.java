package mfs.mw.ussd.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import mfs.mw.ussd.util.Sensitive;
import mfs.mw.ussd.util.SensitiveType;

/**
 * One step as an MNO posts it to {@code POST /ussd/{mno}} (HttpUssdTransport, spec 9.2). JSON for
 * the MVP; a per-MNO template can map other formats later.
 *
 * @param sessionId the MNO's session id
 * @param msisdn the subscriber, as the MNO reports it ({@code 8801...} or {@code 01...})
 * @param serviceCode the dialled code (e.g. {@code *247#})
 * @param input what the subscriber typed on this step (empty on BEGIN)
 * @param type BEGIN, CONTINUE, END or ABORT
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HttpUssdRequest(
    @NotBlank @Size(max = 64) String sessionId,
    @NotBlank @Size(max = 16) @Sensitive(SensitiveType.MSISDN) String msisdn,
    @Size(max = 32) String serviceCode,
    @Size(max = 182) @Sensitive(SensitiveType.SECRET) String input,
    @NotNull UssdEvent type) {}

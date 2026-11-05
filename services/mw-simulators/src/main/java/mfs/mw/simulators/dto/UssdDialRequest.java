package mfs.mw.simulators.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import mfs.mw.simulators.util.Sensitive;
import mfs.mw.simulators.util.SensitiveType;

/**
 * A USSD session the gateway simulator plays (HTTP mode, ticket 26): dial {@code serviceCode} as
 * {@code msisdn} on {@code mno}, then send {@code inputs} one per step.
 *
 * @param sessionId the MNO session id to use; random when absent
 * @param inputs the subscriber's replies in order (may contain a PIN: never logged or recorded)
 * @param resume HTTP mode: continue the open session {@code sessionId} (no BEGIN; ticket 39 plays a
 *     session that is still running when a new menu version goes live)
 */
public record UssdDialRequest(
    @NotBlank @Pattern(regexp = "[a-z]{1,16}") String mno,
    @NotBlank @Sensitive(SensitiveType.MSISDN) String msisdn,
    String serviceCode,
    String sessionId,
    @Sensitive(SensitiveType.SECRET) List<String> inputs,
    Boolean resume) {

  public UssdDialRequest {
    inputs = inputs == null ? List.of() : List.copyOf(inputs);
  }
}

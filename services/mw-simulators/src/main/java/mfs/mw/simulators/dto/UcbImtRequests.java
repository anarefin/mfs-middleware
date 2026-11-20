package mfs.mw.simulators.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Bodies of the UCB IMT mock (ticket 119): the on-demand credit and the confirmation the adapter posts back. */
public final class UcbImtRequests {

  private UcbImtRequests() {}

  /** {@code POST /mock-control/ucb-imt/trigger}. The name and MSISDN are the credit, never logged. */
  public record Trigger(
      @NotBlank @Pattern(regexp = "^[0-9]{1,12}(\\.[0-9]{1,2})?$") String amount,
      @NotBlank @Pattern(regexp = "^(?:\\+?8800?|0)1[3-9][0-9]{8}$") String msisdn,
      @NotBlank @Size(max = 140) String sender,
      @NotBlank @Pattern(regexp = "^[A-Z]{2}$") String country) {}

  /** What {@link #Trigger} pushed. {@code targetStatus} is -1 when nothing was sent or the target was down. */
  public record TriggerResult(String partnerRef, int copies, int targetStatus) {}

  /** {@code POST /ucb-imt/v1/remittances/{reference}/confirmation}, the adapter's CONFIRM_CREDIT body. */
  public record Confirmation(
      @NotBlank @Size(max = 64) String partnerRef,
      @NotBlank @Size(max = 64) String upayRef,
      @NotBlank @Size(max = 32) String outcome) {}
}

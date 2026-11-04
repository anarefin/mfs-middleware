package mfs.mw.adapter.billpay.partner;

import java.util.Map;

/**
 * A verified, mapped callback: the canonical outcome of an earlier PENDING call. Publishing it
 * ({@code mw.partner.inbound.v1}) is the service's job, not the adapter's (ticket 22).
 *
 * @param outcome SUCCESS, FAILED or PENDING
 */
public record InboundResult(
    String partnerRef,
    String reference,
    String outcome,
    String code,
    String message,
    Map<String, String> attributes) {

  public InboundResult {
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }
}

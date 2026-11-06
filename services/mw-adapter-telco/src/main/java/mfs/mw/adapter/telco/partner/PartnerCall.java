package mfs.mw.adapter.telco.partner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One call the service asks the framework to make, in canonical terms.
 *
 * @param partnerId the partner the request names (e.g. {@code gp}); it picks the partner config,
 *     or the default one when the partner has none of its own
 * @param operation canonical operation ({@code PAY}, {@code STATUS_QUERY}, ...); picks the
 *     operation config (path, mappings, definite statuses)
 * @param txnType canonical transaction type, for metrics
 * @param reference our stable partner reference ({@code BITS...}); also {@code {reference}} in a
 *     path template
 * @param input canonical document the request mapping (JSONata) reads; carries personal data,
 *     so it is never logged as it is
 */
public record PartnerCall(
    String partnerId,
    String operation,
    String txnType,
    String txnId,
    String correlationId,
    String reference,
    Map<String, Object> input) {

  public PartnerCall {
    input = input == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(input));
  }

  @Override
  public String toString() {
    return "PartnerCall[partnerId=" + partnerId + ", operation=" + operation + ", txnId=" + txnId + "]";
  }
}

package mfs.mw.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import mfs.mw.auth.util.MaskingPolicy;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/**
 * This service's own type for {@code core.event.v1#WalletStateChanged} (owned by mw-core-mock;
 * ticket 67, D5): the envelope fields the projection needs and the payload. Unknown fields ignored.
 *
 * @param eventType the discriminator; only {@code WalletStateChanged} is projected
 * @param eventId inbox deduplication key
 * @param txnId the event's own (synthetic) txnId, for log correlation only
 * @param payload the wallet state
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WalletStateChanged(String eventType, String eventId, String txnId, State payload) {

  public static final String TYPE = "WalletStateChanged";

  /** The wallet's state at {@code version}. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record State(
      @Sensitive(SensitiveType.MSISDN) String walletId,
      @Sensitive(SensitiveType.MSISDN) String msisdn,
      String walletType,
      String status,
      Boolean hasPin,
      Integer birthYear,
      Long version,
      String change) {

    @Override
    public String toString() {
      return MaskingPolicy.describe(this);
    }
  }
}

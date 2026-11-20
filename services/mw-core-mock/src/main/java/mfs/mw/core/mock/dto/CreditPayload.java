package mfs.mw.core.mock.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * Payload of {@code CreditWallet} (the internal core command; contract {@code core.command.v1} retired in ticket 95b): inbound money, no PIN. The
 * fee the orchestrator may send is ignored; the money comes from the settlement wallet its
 * {@code attributes.settlementWallet} names, else {@code SETTLEMENT} (ticket 94).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreditPayload(String txnType, Party creditParty, Amount amount, Map<String, String> attributes) {

  /** The settlement wallet the money comes from (ticket 94, WAL-10: {@code attributes.settlementWallet}), or null. */
  public String settlementWallet() {
    return attributes == null ? null : attributes.get(TransferPayload.SETTLEMENT_WALLET);
  }
}

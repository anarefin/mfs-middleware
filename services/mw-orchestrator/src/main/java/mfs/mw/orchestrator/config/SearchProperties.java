package mfs.mw.orchestrator.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import mfs.mw.orchestrator.util.SearchKeys;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Transaction search under {@code mw.orchestrator.search} (ticket 35, spec 14.3, 16.3).
 *
 * @param hashKey HMAC key of {@code initiator_hash} and {@code payee_hash} ({@code
 *     util/SearchHash}); a secret, read from OpenBao ({@code secret/mw-orchestrator}), never
 *     written in a config file
 * @param orgPayees per org id, the billers and merchant wallets the org collects for, each {@code
 *     BILLER:<billerCode>} or {@code MERCHANT:<wallet MSISDN>} (A3): an org-scoped query also
 *     returns the transactions paying one of them, tagged {@code PAYEE}. An org that is not listed
 *     sees only what it initiated.
 */
@Validated
@ConfigurationProperties("mw.orchestrator.search")
public record SearchProperties(@NotBlank @Size(min = 32) String hashKey, Map<String, List<String>> orgPayees) {

  public SearchProperties {
    orgPayees = orgPayees == null ? Map.of() : Map.copyOf(orgPayees);
    // fail at start on a malformed entry, not at the first query
    orgPayees.values().forEach(list -> list.forEach(SearchKeys.Key::parse));
  }

  /** The payees {@code orgId} collects for; empty when it collects for none. */
  public List<SearchKeys.Key> payeesOf(String orgId) {
    return orgPayees.getOrDefault(orgId, List.of()).stream().map(SearchKeys.Key::parse).toList();
  }

  @Override
  public String toString() {
    return "SearchProperties[hashKey=****, orgPayees=" + orgPayees.keySet() + "]";
  }
}

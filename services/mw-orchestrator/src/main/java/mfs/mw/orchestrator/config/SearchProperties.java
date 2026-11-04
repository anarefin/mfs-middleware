package mfs.mw.orchestrator.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Transaction search under {@code mw.orchestrator.search} (ticket 35, spec 14.3, 16.3).
 *
 * @param hashKey HMAC key of {@code initiator_hash} ({@code util/SearchHash}); a secret, read from
 *     OpenBao ({@code secret/mw-orchestrator}), never written in a config file
 */
@Validated
@ConfigurationProperties("mw.orchestrator.search")
public record SearchProperties(@NotBlank @Size(min = 32) String hashKey) {

  @Override
  public String toString() {
    return "SearchProperties[hashKey=****]";
  }
}

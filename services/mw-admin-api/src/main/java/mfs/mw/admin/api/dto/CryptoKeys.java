package mfs.mw.admin.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * mw-crypto's key metadata and ceremony answers (contract {@code api/mw-crypto/crypto-internal.v1},
 * ticket 75), as read here and shown on {@code GET /admin/v1/keys}. Nothing secret: metadata, KCVs.
 */
public final class CryptoKeys {

  private CryptoKeys() {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Page(List<Key> items) {
    public Page {
      items = items == null ? List.of() : List.copyOf(items);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Key(String keyId, String purpose, String algorithm, String provider, List<Version> versions) {
    public Key {
      versions = versions == null ? List.of() : List.copyOf(versions);
    }

    /** The ACTIVE version, or null. */
    public Version active() {
      return versions.stream().filter(v -> "ACTIVE".equals(v.state())).findFirst().orElse(null);
    }

    /** The newest version, or null. */
    public Version newest() {
      return versions.isEmpty() ? null : versions.getLast();
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Version(int version, String kid, String state, Instant createdAt, Instant rotatedAt) {}

  /** A ceremony as mw-crypto reports it. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Ceremony(
      UUID ceremonyId, String keyId, String purpose, int version, String kid, String state, List<Share> shares,
      String checkValue) {
    public Ceremony {
      shares = shares == null ? List.of() : List.copyOf(shares);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Share(int share, String custodian, Instant enteredAt) {}
}

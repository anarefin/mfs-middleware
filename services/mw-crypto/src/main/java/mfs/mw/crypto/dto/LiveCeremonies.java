package mfs.mw.crypto.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /internal/v1/ceremonies?state=LIVE} (ticket 75): the OPEN and COMBINED ceremonies, for
 * mw-admin-api's reconciler, which aborts those whose change request was rejected, expired or never
 * committed.
 */
public record LiveCeremonies(List<Item> items) {

  public LiveCeremonies {
    items = List.copyOf(items);
  }

  public record Item(UUID ceremonyId, String keyId, String state, Instant createdAt) {}
}

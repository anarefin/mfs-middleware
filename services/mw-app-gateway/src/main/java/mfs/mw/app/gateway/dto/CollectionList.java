package mfs.mw.app.gateway.dto;

import java.util.List;

/** {@code GET /app/v1/merchant/collections} (ticket 30): newest first. */
public record CollectionList(List<MerchantCollection> collections) {

  public CollectionList {
    collections = List.copyOf(collections);
  }
}

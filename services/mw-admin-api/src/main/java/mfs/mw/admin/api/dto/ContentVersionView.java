package mfs.mw.admin.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A version of a USSD menu or notify template as {@code admin.v1} shows it (ticket 39). {@code
 * value} (the tree or the texts, with the go-live schedule) is left out of lists.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContentVersionView(
    String configKey,
    int version,
    String status,
    String changeRequestId,
    String makerId,
    String checkerId,
    Instant createdAt,
    Instant decidedAt,
    Map<String, Object> value) {

  /** One page, newest first. */
  public record Page(List<ContentVersionView> items, int limit, int offset) {

    public Page {
      items = List.copyOf(items);
    }
  }
}

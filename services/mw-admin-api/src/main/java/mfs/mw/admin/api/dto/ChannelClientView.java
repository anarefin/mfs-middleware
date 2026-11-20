package mfs.mw.admin.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * A channel client as {@code admin.v1} shows it (ticket 100a): its definition, the approved
 * version, the APISIX consumer it is projected to (null when its auth method has none) and who
 * changed it last.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChannelClientView(
    String clientId,
    ChannelClientSpec.ClientType type,
    ChannelClientSpec.AuthMethod authMethod,
    List<String> allowedRoutes,
    List<String> ipAllowList,
    Long ratePerSec,
    Long dailyQuota,
    boolean enabled,
    long version,
    String consumer,
    Instant updatedAt,
    String updatedBy,
    String approvedBy) {

  /** Every client, by id. */
  public record Page(List<ChannelClientView> items) {
    public Page {
      items = List.copyOf(items);
    }
  }
}

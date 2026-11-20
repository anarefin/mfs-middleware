package mfs.mw.seeder.dto;

import java.util.List;

/**
 * A channel client as {@code GET /admin/v1/channel-clients/{clientId}} answers it (admin.v1
 * {@code ChannelClient}, ticket 100a); the fields the seeder compares.
 */
public record ChannelClientView(
    String clientId,
    String type,
    String authMethod,
    List<String> allowedRoutes,
    List<String> ipAllowList,
    Long ratePerSec,
    Long dailyQuota,
    boolean enabled,
    long version) {

  /** Its definition, comparable with a seed's. */
  public ChannelClientDefinition definition() {
    return new ChannelClientDefinition(type, authMethod, allowedRoutes, ipAllowList, ratePerSec, dailyQuota, enabled);
  }
}

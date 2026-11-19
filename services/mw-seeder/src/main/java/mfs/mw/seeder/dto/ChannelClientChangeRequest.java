package mfs.mw.seeder.dto;

/**
 * Body of {@code POST /admin/v1/channel-clients/{clientId}/change-requests} (admin.v1, ticket
 * 100a): UPSERT with the full definition.
 */
public record ChannelClientChangeRequest(String action, String reason, ChannelClientDefinition client) {

  public static ChannelClientChangeRequest upsert(ChannelClientDefinition client, String reason) {
    return new ChannelClientChangeRequest("UPSERT", reason, client);
  }
}

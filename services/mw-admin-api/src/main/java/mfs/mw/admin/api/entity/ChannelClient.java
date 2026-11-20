package mfs.mw.admin.api.entity;

import java.time.Instant;
import mfs.mw.admin.api.dto.ChannelClientSpec;

/**
 * One row of {@code admin.channel_client} (ticket 100a): the registry entry of a channel client,
 * the source of truth APISIX is projected from. {@code version} is the approved version (1 for the
 * first approval); no secret is ever stored here.
 */
public record ChannelClient(
    String clientId, ChannelClientSpec spec, long version, Instant updatedAt, String updatedBy, String approvedBy) {

  /** {@code ChangeRequest.resourceType} of a channel client request. */
  public static final String RESOURCE_TYPE = "channel_client";
}

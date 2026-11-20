package mfs.mw.seeder.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A channel client's definition (admin.v1 {@code ChannelClientSpec}, ticket 100a): what the seeder
 * files and compares. No secret: a BASIC client's password is in Vault ({@code apisix/<clientId>}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChannelClientDefinition(
    String type,
    String authMethod,
    List<String> allowedRoutes,
    List<String> ipAllowList,
    Long ratePerSec,
    Long dailyQuota,
    boolean enabled) {

  public ChannelClientDefinition {
    allowedRoutes = allowedRoutes == null ? List.of() : List.copyOf(allowedRoutes);
    ipAllowList = ipAllowList == null ? List.of() : List.copyOf(ipAllowList);
  }
}

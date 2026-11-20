package mfs.mw.admin.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A channel client's definition as {@code admin.v1} names it (ticket 100a, {@code
 * ChannelClientSpec}): who calls the edge, how it authenticates, which APISIX routes it may use and
 * its limits. Never a secret: the credential of a BASIC client lives in Vault ({@code
 * apisix/<clientId>}, field {@code password}).
 *
 * @param allowedRoutes APISIX route ids
 * @param ipAllowList source addresses or CIDRs; empty = any source
 * @param ratePerSec requests per second ({@code limit-req}); null = none
 * @param dailyQuota requests per 24 h ({@code limit-count}); null = none
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChannelClientSpec(
    @NotNull ClientType type,
    @NotNull AuthMethod authMethod,
    @NotNull @Size(max = 64) List<String> allowedRoutes,
    @NotNull @Size(max = 64) List<String> ipAllowList,
    @Min(1) @Max(100_000) Long ratePerSec,
    @Min(1) @Max(1_000_000_000) Long dailyQuota,
    @NotNull Boolean enabled) {

  public ChannelClientSpec {
    allowedRoutes = allowedRoutes == null ? List.of() : List.copyOf(allowedRoutes);
    ipAllowList = ipAllowList == null ? List.of() : List.copyOf(ipAllowList);
  }

  public boolean isEnabled() {
    return Boolean.TRUE.equals(enabled);
  }

  /** The kinds of channel client of FRS PLT-003. */
  public enum ClientType {
    APP,
    MNO,
    PARTNER,
    MERCHANT,
    PORTAL
  }

  /** How a client authenticates at the edge. */
  public enum AuthMethod {
    JWT,
    OAUTH2_CC,
    MTLS,
    HMAC,
    BASIC;

    /**
     * Whether a client of this method is an APISIX consumer (projected by {@code
     * ApisixConsumerSync}). BASIC only: JWT tokens are checked per route, HMAC by the service
     * (merchants, partner callbacks). OAUTH2_CC has no consumer (ticket 100b): its routes get
     * {@code openid-connect} and a limit keyed by the token client id. MTLS is ticket 100c.
     */
    public boolean hasConsumer() {
      return this == BASIC;
    }
  }
}

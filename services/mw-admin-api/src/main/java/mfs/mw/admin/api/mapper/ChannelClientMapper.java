package mfs.mw.admin.api.mapper;

import mfs.mw.admin.api.dto.ChannelClientSpec;
import mfs.mw.admin.api.dto.ChannelClientView;
import mfs.mw.admin.api.entity.ChannelClient;

/** {@link ChannelClient} -> {@code admin.v1} view (ticket 100a). */
public final class ChannelClientMapper {

  private ChannelClientMapper() {}

  public static ChannelClientView view(ChannelClient c) {
    ChannelClientSpec s = c.spec();
    return new ChannelClientView(
        c.clientId(), s.type(), s.authMethod(), s.allowedRoutes(), s.ipAllowList(), s.ratePerSec(), s.dailyQuota(),
        s.isEnabled(), c.version(), s.authMethod().hasConsumer() ? consumerName(c.clientId()) : null, c.updatedAt(),
        c.updatedBy(), c.approvedBy());
  }

  /** The APISIX consumer of a client id: {@code -} turned into {@code _} (e.g. {@code ussd_gp}). */
  public static String consumerName(String clientId) {
    return clientId.replace('-', '_');
  }
}

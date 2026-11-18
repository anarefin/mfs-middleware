package mfs.mw.adapter.telco.partner.route;

import org.apache.camel.component.http.HttpComponent;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
import org.apache.hc.core5.util.TimeValue;

/**
 * Camel's {@code http}/{@code https} component with the partner resolver ({@link
 * PartnerDnsResolver}) on its connection manager: the same manager Camel 4.22 builds ({@code
 * HttpComponent.createConnectionManager}: time to live, strict pool, socket config, pool sizes),
 * plus {@code setDnsResolver}. Bound as the beans {@code http} and {@code https}, so every partner
 * call of {@code mw-http-call} connects only to checked addresses. This service makes no other HTTP
 * calls through Camel.
 */
public final class PartnerHttpComponent extends HttpComponent {

  private final PartnerDnsResolver resolver;

  public PartnerHttpComponent(PartnerDnsResolver resolver) {
    this.resolver = resolver;
  }

  public PartnerDnsResolver resolver() {
    return resolver;
  }

  @Override
  protected HttpClientConnectionManager createConnectionManager(
      TlsSocketStrategy tls, int maxTotalConnections, int connectionsPerRoute, SocketConfig socketConfig) {
    ConnectionConfig connection =
        ConnectionConfig.custom().setTimeToLive(TimeValue.ofMilliseconds(getConnectionTimeToLive())).build();
    PoolingHttpClientConnectionManagerBuilder builder =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setTlsSocketStrategy(tls)
            .setPoolConcurrencyPolicy(PoolConcurrencyPolicy.STRICT)
            .setDefaultConnectionConfig(connection)
            .setDefaultSocketConfig(socketConfig)
            .setDnsResolver(resolver);
    int total = maxTotalConnections == 0 ? getMaxTotalConnections() : maxTotalConnections;
    if (total > 0) {
      builder.setMaxConnTotal(total);
    }
    int perRoute = connectionsPerRoute == 0 ? getConnectionsPerRoute() : connectionsPerRoute;
    if (perRoute > 0) {
      builder.setMaxConnPerRoute(perRoute);
    }
    return builder.build();
  }
}

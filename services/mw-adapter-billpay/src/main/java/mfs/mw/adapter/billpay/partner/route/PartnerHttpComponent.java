package mfs.mw.adapter.billpay.partner.route;

import java.util.Map;
import org.apache.camel.component.http.HttpComponent;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
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
 *
 * <p>Automatic retries and redirect handling are off for every client it builds (plain, mTLS
 * {@code sslContextParameters} and the OAuth2 token call alike): HttpClient 5's default retry
 * strategy resends a POST by itself after a 503 or 429 (and on some I/O errors), outside the
 * breaker, the audit and the no-redelivery rule of a PAY; a partner that applies the resend turns a
 * FAILED txn into a COMPLETED one. The only resend is {@code MwHttpStep}'s deliberate one after a
 * 401 (ADR D28).
 */
public final class PartnerHttpComponent extends HttpComponent {

  private final PartnerDnsResolver resolver;

  public PartnerHttpComponent(PartnerDnsResolver resolver) {
    this.resolver = resolver;
  }

  /**
   * Camel's builder with retries and redirects always off. Set here rather than through {@code
   * setAutomaticRetriesDisabled}: camel-http-starter writes its {@code camel.component.http*}
   * defaults ({@code false}) over the bean's fields after it is built.
   */
  @Override
  protected HttpClientBuilder createHttpClientBuilder(
      String uri, Map<String, Object> parameters, Map<String, Object> httpClientOptions) {
    return super.createHttpClientBuilder(uri, parameters, httpClientOptions)
        .disableAutomaticRetries()
        .disableRedirectHandling();
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

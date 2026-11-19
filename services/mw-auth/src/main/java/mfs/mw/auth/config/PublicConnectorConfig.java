package mfs.mw.auth.config;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * mw-auth listens on two ports (spec 18.7, D24, ticket 67). {@code server.port} (8443) is the
 * internal API {@code /internal/v1/**}, set up as mw-crypto's: mTLS with {@code client-auth: need}
 * on this service's Vault certificate ({@code server.ssl}, {@link ServiceCertificateManager}), so
 * the certificate renews without a restart. This class adds the public plain-HTTP connector
 * {@code mw.auth.public-port} (8080) for {@code /auth/v1/**} behind APISIX (tickets 68-69). {@link
 * PublicConnector} tells the security rules which requests came in on it: {@code /internal/**} is
 * refused there whatever the token.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class PublicConnectorConfig {

  @Bean
  PublicConnector publicConnector(@Value("${mw.auth.public-port}") int port) {
    Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
    connector.setPort(port);
    return new PublicConnector(connector);
  }

  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> publicConnectorCustomizer(PublicConnector publicConnector) {
    return factory -> factory.addAdditionalConnectors(publicConnector.connector());
  }

  /** The public connector; its local port is known once Tomcat started (0 = a random port). */
  public record PublicConnector(Connector connector) {

    /** Whether the request arrived on the public port. */
    public boolean received(jakarta.servlet.http.HttpServletRequest request) {
      int local = connector.getLocalPort();
      return local > 0 && request.getLocalPort() == local;
    }

    public int localPort() {
      return connector.getLocalPort();
    }
  }
}

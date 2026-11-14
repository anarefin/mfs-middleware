package mfs.mw.crypto.config;

import mfs.mw.crypto.filter.TestApiPortFilter;
import org.apache.catalina.connector.Connector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * The dev-only test connector (ticket 76, {@link TestApiProperties}): only with {@code
 * mw.crypto.test-api.enabled=true}. {@code server.port} stays the mTLS internal API; this adds a plain
 * connector for {@code /test/v1/payshield/**} and the filter that keeps the two apart ({@link
 * TestApiPortFilter}: {@code /test/**} only on this connector, nothing else on it).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "mw.crypto.test-api.enabled", havingValue = "true")
public class TestApiConfig {

  @Bean
  TestApiPortFilter.TestConnector testConnector(TestApiProperties props) {
    Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
    connector.setPort(props.port());
    return new TestApiPortFilter.TestConnector(connector);
  }

  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> testConnectorCustomizer(
      TestApiPortFilter.TestConnector connector) {
    return factory -> factory.addAdditionalConnectors(connector.connector());
  }

  @Bean
  FilterRegistrationBean<TestApiPortFilter> testApiPortFilter(TestApiPortFilter.TestConnector connector) {
    FilterRegistrationBean<TestApiPortFilter> bean =
        new FilterRegistrationBean<>(new TestApiPortFilter(connector::received));
    bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return bean;
  }
}

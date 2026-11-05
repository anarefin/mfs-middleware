package mfs.mw.checkout.config;

import java.net.InetAddress;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import javax.net.ssl.SSLContext;
import mfs.mw.checkout.security.IpnUrlPolicy;
import mfs.mw.checkout.service.IpnRetrySchedule;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The signed IPN (ticket 32): its properties, retry schedule and URL policy. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IpnProperties.class)
public class IpnConfig {

  @Bean
  public IpnRetrySchedule ipnRetrySchedule(IpnProperties props, Clock clock) {
    return new IpnRetrySchedule(props.retryDelays(), clock);
  }

  /** TLS of merchant IPN calls: the JVM's default trust store (merchants have public certificates). */
  @Bean
  public SSLContext merchantIpnTls() throws NoSuchAlgorithmException {
    return SSLContext.getDefault();
  }

  @Bean
  public IpnUrlPolicy ipnUrlPolicy(IpnProperties props) {
    return new IpnUrlPolicy(props.devHosts(), InetAddress::getAllByName);
  }
}

package mfs.mw.admin.api.config;

import java.security.SecureRandom;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Partner credential upload (ticket 131): the random source of generated key store passwords. */
@Configuration(proxyBeanMethods = false)
public class CredentialConfig {

  @Bean
  @ConditionalOnMissingBean
  public SecureRandom credentialRandom() {
    return new SecureRandom();
  }
}

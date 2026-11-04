package mfs.mw.core.mock.config;

import mfs.mw.core.mock.service.WalletSeeder;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Beans of the core mock: bcrypt for PIN hashes and the seed runner. */
@Configuration(proxyBeanMethods = false)
public class CoreMockConfig {

  @Bean
  PasswordEncoder pinEncoder(CoreMockProperties props) {
    return new BCryptPasswordEncoder(props.pin().bcryptStrength());
  }

  /** Runs after the context refresh, so the AOT training run (exit on refresh) needs no database. */
  @Bean
  ApplicationRunner seedOnStart(WalletSeeder seeder, CoreMockProperties props) {
    return args -> {
      if (props.seed().enabled()) {
        seeder.seed();
      }
    };
  }
}

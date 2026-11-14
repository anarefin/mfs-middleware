package mfs.mw.core.mock.config;

import mfs.mw.core.mock.service.WalletSeeder;
import mfs.mw.core.mock.service.WalletStateEvents;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Beans of the core mock: bcrypt for PIN hashes and the seed (and wallet snapshot) runner. */
@Configuration(proxyBeanMethods = false)
public class CoreMockConfig {

  @Bean
  PasswordEncoder pinEncoder(CoreMockProperties props) {
    return new BCryptPasswordEncoder(props.pin().bcryptStrength());
  }

  /**
   * Runs after the context refresh, so the AOT training run (exit on refresh) needs no database.
   * With {@code mw.coremock.wallet-snapshot-on-start} (dev stack: {@code MW_COREMOCK_WALLET_SNAPSHOT})
   * every wallet is then published as WalletStateChanged SNAPSHOT (ticket 67): mw-auth's projection
   * catches up with a ledger it missed (idempotent by version).
   */
  @Bean
  ApplicationRunner seedOnStart(
      WalletSeeder seeder,
      WalletStateEvents stateEvents,
      CoreMockProperties props,
      @Value("${mw.coremock.wallet-snapshot-on-start:false}") boolean snapshot) {
    return args -> {
      if (props.seed().enabled()) {
        seeder.seed();
      }
      if (snapshot) {
        stateEvents.snapshot();
      }
    };
  }
}

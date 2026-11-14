package mfs.mw.simulators.config;

import mfs.mw.simulators.client.InMemoryLmkStore;
import mfs.mw.simulators.client.LmkStore;
import mfs.mw.simulators.client.Pkcs11LmkStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The payShield simulator's LMK store (ticket 64): the paysim token, or memory in host tests. */
@Configuration
public class PayShieldConfig {

  @Bean
  LmkStore lmkStore(PayShieldProperties props) {
    return switch (props.lmkStore()) {
      case "memory" -> new InMemoryLmkStore();
      case "pkcs11" -> new Pkcs11LmkStore(props.pkcs11());
      default -> throw new IllegalStateException("sim.payshield.lmk-store must be pkcs11 or memory");
    };
  }
}

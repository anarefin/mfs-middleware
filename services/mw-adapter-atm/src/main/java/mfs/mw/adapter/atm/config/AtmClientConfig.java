package mfs.mw.adapter.atm.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Executor for partner calls, so a slow atm system never holds a Kafka consumer thread. */
@Configuration(proxyBeanMethods = false)
public class AtmClientConfig {

  @Bean(name = "atmCallExecutor", destroyMethod = "close")
  ExecutorService atmCallExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
}

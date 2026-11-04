package mfs.mw.adapter.telco.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Executor for partner calls, so a slow OCS never holds a Kafka consumer thread. */
@Configuration(proxyBeanMethods = false)
public class TelcoClientConfig {

  @Bean(name = "telcoCallExecutor", destroyMethod = "close")
  ExecutorService telcoCallExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
}

package mfs.mw.adapter.remit.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Executor for partner calls, so a slow remit system never holds a Kafka consumer thread. */
@Configuration(proxyBeanMethods = false)
public class RemitClientConfig {

  @Bean(name = "remitCallExecutor", destroyMethod = "close")
  ExecutorService remitCallExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
}

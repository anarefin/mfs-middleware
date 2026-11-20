package mfs.mw.adapter.merchant.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Executor for partner calls, so a slow merchant system never holds a Kafka consumer thread. */
@Configuration(proxyBeanMethods = false)
public class MerchantClientConfig {

  @Bean(name = "merchantCallExecutor", destroyMethod = "close")
  ExecutorService merchantCallExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
}

package mfs.mw.adapter.bank.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Executor for partner calls, so a slow bank never holds a Kafka consumer thread. */
@Configuration(proxyBeanMethods = false)
public class BankClientConfig {

  @Bean(name = "bankCallExecutor", destroyMethod = "close")
  ExecutorService bankCallExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
}

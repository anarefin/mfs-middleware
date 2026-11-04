package mfs.mw.adapter.billpay.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Executor for partner calls, so a slow aggregator never holds a Kafka consumer thread. */
@Configuration(proxyBeanMethods = false)
public class BillpayClientConfig {

  @Bean(name = "billpayCallExecutor", destroyMethod = "close")
  ExecutorService billpayCallExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
}

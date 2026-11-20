package mfs.mw.auth.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import mfs.mw.auth.service.PinResetService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The core wallet calls (ADR D31, ticket 95b): the PIN reset's factors check runs on a virtual thread
 * after the reset's commit, never on the request thread ({@code service/PinResetService}).
 */
@Configuration(proxyBeanMethods = false)
public class CoreClientConfig {

  @Bean(name = PinResetService.KYC_EXECUTOR, destroyMethod = "close")
  ExecutorService kycCheckExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
}

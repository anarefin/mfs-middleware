package mfs.mw.checkout.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@code mw.checkout.*} (spec 9.3). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CheckoutProperties.class)
public class CheckoutConfig {}

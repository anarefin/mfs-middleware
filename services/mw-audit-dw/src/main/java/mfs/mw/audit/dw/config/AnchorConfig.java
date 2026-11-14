package mfs.mw.audit.dw.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link AnchorProperties} (the signed daily audit anchor, ticket 74). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnchorProperties.class)
public class AnchorConfig {}

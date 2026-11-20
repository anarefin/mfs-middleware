package mfs.mw.audit.dw.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link AuditQueryProperties} (search projection, timeline and CSV export, ticket 130). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuditQueryProperties.class)
public class AuditQueryConfig {}

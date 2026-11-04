package mfs.mw.audit.dw.config;

import com.zaxxer.hikari.HikariDataSource;
import mfs.mw.audit.dw.service.AuditChainVerifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The read-only {@code audit_reader} connection for the chain verification (ticket 50): a
 * one-connection, read-only pool on the service's own database URL, opened on first use. It is
 * deliberately not a {@code DataSource} or {@code JdbcTemplate} bean, so the service's own
 * (writer) data source and template stay the auto-configured ones.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChainVerifyProperties.class)
@ConditionalOnProperty(name = "mw.audit-dw.chain-verify.enabled", havingValue = "true", matchIfMissing = true)
public class AuditReaderConfig {

  /** Owns the reader pool; closed with the context. */
  public static final class AuditReader implements AutoCloseable {
    private final HikariDataSource dataSource;

    AuditReader(HikariDataSource dataSource) {
      this.dataSource = dataSource;
    }

    public JdbcTemplate jdbc() {
      return new JdbcTemplate(dataSource);
    }

    @Override
    public void close() {
      dataSource.close();
    }
  }

  @Bean(destroyMethod = "close")
  AuditReader auditReader(JdbcConnectionDetails connection, ChainVerifyProperties props) {
    HikariDataSource ds = new HikariDataSource();
    ds.setPoolName("audit-reader");
    ds.setJdbcUrl(connection.getJdbcUrl());
    ds.setUsername(props.readerUsername());
    ds.setPassword(props.readerPassword());
    ds.setSchema("audit");
    ds.setReadOnly(true);
    ds.setMaximumPoolSize(1);
    ds.setMinimumIdle(0);
    return new AuditReader(ds);
  }

  @Bean
  AuditChainVerifier auditChainVerifier(AuditReader reader) {
    return new AuditChainVerifier(reader.jdbc());
  }
}

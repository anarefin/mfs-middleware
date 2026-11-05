package mfs.mw.audit.dw.config;

import com.zaxxer.hikari.HikariDataSource;
import mfs.mw.audit.dw.repository.AuditSearchRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The audit search (ticket 35) reads as the read-only {@code audit_reader} (the role and password
 * of {@code mw.audit-dw.chain-verify.reader-*}, password in OpenBao): its own small read-only pool,
 * lazy like the verifier's, so the INSERT-only writer data source stays the only {@code
 * DataSource} bean and a search never waits for the verify job's single connection.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChainVerifyProperties.class)
public class AuditSearchConfig {

  /** Owns the search pool; closed with the context. */
  public static final class SearchReader implements AutoCloseable {
    private final HikariDataSource dataSource;

    SearchReader(HikariDataSource dataSource) {
      this.dataSource = dataSource;
    }

    JdbcTemplate jdbc() {
      return new JdbcTemplate(dataSource);
    }

    @Override
    public void close() {
      dataSource.close();
    }
  }

  @Bean(destroyMethod = "close")
  SearchReader auditSearchReader(JdbcConnectionDetails connection, ChainVerifyProperties props) {
    HikariDataSource ds = new HikariDataSource();
    ds.setPoolName("audit-search");
    ds.setJdbcUrl(connection.getJdbcUrl());
    ds.setUsername(props.readerUsername());
    ds.setPassword(props.readerPassword());
    ds.setSchema("audit");
    ds.setReadOnly(true);
    ds.setMaximumPoolSize(4);
    ds.setMinimumIdle(0);
    return new SearchReader(ds);
  }

  @Bean
  AuditSearchRepository auditSearchRepository(SearchReader reader, JsonMapper json) {
    return new AuditSearchRepository(reader.jdbc(), json);
  }
}

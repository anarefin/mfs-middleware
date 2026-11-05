package mfs.mw.orchestrator.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.util.SearchHash;
import mfs.mw.orchestrator.util.SearchKeys;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Flyway migration 10 (A2/A3), in Java because it needs the search hash key from OpenBao: every
 * row written before V9 is re-hashed as {@code HMAC(<type>:<id>)} from its raw {@code
 * initiator_id} and stored request, and gets its {@code initiator_type} and {@code payee_hash}.
 * Spring Boot hands this bean to Flyway, which runs it after V9 and once only.
 */
@Component
public class SearchHashMigration implements JavaMigration {

  private static final MigrationVersion VERSION = MigrationVersion.fromVersion("10");

  private final SearchHash hash;
  private final JsonMapper json;

  public SearchHashMigration(SearchHash hash, JsonMapper json) {
    this.hash = hash;
    this.json = json;
  }

  @Override
  public MigrationVersion getVersion() {
    return VERSION;
  }

  @Override
  public String getDescription() {
    return "typed search hash and payee hash";
  }

  @Override
  public Integer getChecksum() {
    return null;
  }

  @Override
  public boolean canExecuteInTransaction() {
    return true;
  }

  @Override
  public void migrate(Context context) throws SQLException {
    rehash(context.getConnection());
  }

  /** Re-hashes every transaction row on {@code connection}; returns how many it updated. */
  public int rehash(Connection connection) throws SQLException {
    int updated = 0;
    try (PreparedStatement select =
            connection.prepareStatement("select txn_id, initiator_id, request::text from transaction");
        PreparedStatement update =
            connection.prepareStatement(
                "update transaction set initiator_type = ?, initiator_hash = ?, payee_hash = ? where txn_id = ?");
        ResultSet rs = select.executeQuery()) {
      while (rs.next()) {
        TxnRequest request = json.readValue(rs.getString(3), TxnRequest.class);
        String type = request.initiator() == null ? null : request.initiator().type();
        String id = rs.getString(2);
        Optional<SearchKeys.Key> payee = SearchKeys.payee(request);
        update.setString(1, type);
        update.setBytes(2, type == null || id == null ? null : hash.of(type, id));
        update.setBytes(3, payee.map(hash::of).orElse(null));
        update.setObject(4, rs.getObject(1, UUID.class));
        update.addBatch();
        updated++;
      }
      update.executeBatch();
    }
    return updated;
  }
}

package mfs.mw.orchestrator.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
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
 * Flyway migration 13 (ADR D25), in Java because it needs the search hash key from OpenBao: every
 * transaction written before V12 gets its {@code wallet_txn} rows ({@link
 * SearchKeys#walletParties}, keyed {@code HMAC(WALLET:<MSISDN>)}) from its stored request, so an
 * old transaction is in the wallet history exactly like a new one. A row already there is left
 * alone ({@code on conflict do nothing}). Spring Boot hands this bean to Flyway, which runs it
 * after V12 and once only.
 */
@Component
public class WalletPartyMigration implements JavaMigration {

  private static final MigrationVersion VERSION = MigrationVersion.fromVersion("13");

  private final SearchHash hash;
  private final JsonMapper json;

  public WalletPartyMigration(SearchHash hash, JsonMapper json) {
    this.hash = hash;
    this.json = json;
  }

  @Override
  public MigrationVersion getVersion() {
    return VERSION;
  }

  @Override
  public String getDescription() {
    return "wallet party index backfill";
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
    backfill(context.getConnection());
  }

  /** Writes the wallet rows of every transaction on {@code connection}; returns how many it added. */
  public int backfill(Connection connection) throws SQLException {
    int added = 0;
    try (PreparedStatement select =
            connection.prepareStatement("select txn_id, created_at, request::text from transaction");
        PreparedStatement insert =
            connection.prepareStatement(
                "insert into wallet_txn (wallet_hash, txn_id, direction, created_at) values (?, ?, ?, ?)"
                    + " on conflict do nothing");
        ResultSet rs = select.executeQuery()) {
      while (rs.next()) {
        UUID txnId = rs.getObject(1, UUID.class);
        Timestamp createdAt = rs.getTimestamp(2);
        TxnRequest request = json.readValue(rs.getString(3), TxnRequest.class);
        for (SearchKeys.WalletParty p : SearchKeys.walletParties(request)) {
          insert.setBytes(1, hash.of(p.key()));
          insert.setObject(2, txnId);
          insert.setString(3, p.direction());
          insert.setTimestamp(4, createdAt);
          insert.addBatch();
        }
      }
      for (int n : insert.executeBatch()) {
        added += Math.max(n, 0);
      }
    }
    return added;
  }
}

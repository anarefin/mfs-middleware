package mfs.mw.compliance.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.compliance.dto.Channel;
import mfs.mw.compliance.dto.DocumentType;
import mfs.mw.compliance.dto.KycLevel;
import mfs.mw.compliance.dto.KycStatus;
import mfs.mw.compliance.entity.Consent;
import mfs.mw.compliance.entity.KycApplication;
import mfs.mw.compliance.entity.KycDocument;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@code compliance} schema's consent, application and document tables (JDBC, own schema only). */
@Repository
public class KycStore {

  private final JdbcClient jdbc;

  public KycStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insertConsent(Consent c) {
    jdbc.sql("""
        INSERT INTO consent (id, wallet_id, purpose, channel, agent_id, granted_at)
        VALUES (:id, :wallet, :purpose, :channel, :agent, :granted)""")
        .param("id", c.id()).param("wallet", c.walletId()).param("purpose", c.purpose())
        .param("channel", c.channel().name()).param("agent", c.agentId())
        .param("granted", Timestamp.from(c.grantedAt()))
        .update();
  }

  /** Whether the consent row is committed (read on its own, outside the EC call). */
  public boolean consentExists(UUID consentId) {
    return jdbc.sql("SELECT count(*) FROM consent WHERE id = :id").param("id", consentId)
        .query(Long.class).single() > 0;
  }

  public void insertApplication(KycApplication a) {
    jdbc.sql("""
        INSERT INTO kyc_application (id, wallet_id, channel, agent_id, consent_id, status, nid_token, nid_last3,
          nid_enc, dob_enc, name_enc, correlation_id, created_at, updated_at)
        VALUES (:id, :wallet, :channel, :agent, :consent, :status, :token, :last3,
          CAST(:nid AS jsonb), CAST(:dob AS jsonb), CAST(:name AS jsonb), :corr, :at, :at)""")
        .param("id", a.id()).param("wallet", a.walletId()).param("channel", a.channel().name())
        .param("agent", a.agentId()).param("consent", a.consentId()).param("status", a.status().name())
        .param("token", a.nidToken()).param("last3", a.nidLast3())
        .param("nid", a.nidEnc()).param("dob", a.dobEnc()).param("name", a.nameEnc())
        .param("corr", a.correlationId()).param("at", Timestamp.from(a.createdAt()))
        .update();
  }

  public Optional<KycApplication> findApplication(UUID id) {
    return jdbc.sql("SELECT * FROM kyc_application WHERE id = :id").param("id", id)
        .query(KycStore::application).optional();
  }

  /** Locks the row for a state change (FOR UPDATE). */
  public Optional<KycApplication> lockApplication(UUID id) {
    return jdbc.sql("SELECT * FROM kyc_application WHERE id = :id FOR UPDATE").param("id", id)
        .query(KycStore::application).optional();
  }

  public void updateStatus(UUID id, KycStatus status, String errorCode, Instant at) {
    jdbc.sql("UPDATE kyc_application SET status = :status, error_code = :code, updated_at = :at WHERE id = :id")
        .param("status", status.name()).param("code", errorCode).param("at", Timestamp.from(at)).param("id", id)
        .update();
  }

  public void recordOutcome(
      UUID id, KycStatus status, String ecNameEnc, double score, KycLevel level, String reason, Instant at) {
    jdbc.sql("""
        UPDATE kyc_application SET status = :status, ec_name_enc = CAST(:ec AS jsonb), match_score = :score,
          kyc_level = :level, reason = :reason, error_code = NULL, updated_at = :at WHERE id = :id""")
        .param("status", status.name()).param("ec", ecNameEnc).param("score", score)
        .param("level", level == null ? null : level.name()).param("reason", reason)
        .param("at", Timestamp.from(at)).param("id", id)
        .update();
  }

  /** Inserts or replaces the application's image of that type. */
  public void upsertDocument(KycDocument d) {
    jdbc.sql("""
        INSERT INTO kyc_document (id, application_id, type, content_type, size_bytes, sha256, blob_enc)
        VALUES (:id, :app, :type, :ct, :size, :sha, CAST(:blob AS jsonb))
        ON CONFLICT (application_id, type) DO UPDATE SET id = EXCLUDED.id, content_type = EXCLUDED.content_type,
          size_bytes = EXCLUDED.size_bytes, sha256 = EXCLUDED.sha256, blob_enc = EXCLUDED.blob_enc,
          created_at = now()""")
        .param("id", d.id()).param("app", d.applicationId()).param("type", d.type().name())
        .param("ct", d.contentType()).param("size", d.sizeBytes()).param("sha", d.sha256())
        .param("blob", d.blobEnc())
        .update();
  }

  public List<DocumentType> documentTypes(UUID applicationId) {
    return jdbc.sql("SELECT type FROM kyc_document WHERE application_id = :id ORDER BY type").param("id", applicationId)
        .query((rs, n) -> DocumentType.valueOf(rs.getString(1))).list();
  }

  private static KycApplication application(ResultSet rs, int n) throws SQLException {
    String level = rs.getString("kyc_level");
    java.math.BigDecimal score = rs.getBigDecimal("match_score");
    return new KycApplication(
        rs.getObject("id", UUID.class), rs.getString("wallet_id"), Channel.valueOf(rs.getString("channel")),
        rs.getString("agent_id"), rs.getObject("consent_id", UUID.class), KycStatus.valueOf(rs.getString("status")),
        rs.getString("nid_token"), rs.getString("nid_last3"), rs.getString("nid_enc"), rs.getString("dob_enc"),
        rs.getString("name_enc"), rs.getString("ec_name_enc"), score == null ? null : score.doubleValue(),
        level == null ? null : KycLevel.valueOf(level), rs.getString("reason"), rs.getString("error_code"),
        rs.getString("correlation_id"), rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant());
  }
}

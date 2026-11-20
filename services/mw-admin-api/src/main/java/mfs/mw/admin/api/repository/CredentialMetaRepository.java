package mfs.mw.admin.api.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.admin.api.dto.CredentialType;
import mfs.mw.admin.api.entity.CredentialMeta;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@code admin.credential_meta} and {@code admin.credential_alert} (ticket 131), plain JDBC. */
@Repository
public class CredentialMetaRepository {

  private static final TypeReference<List<String>> LIST = new TypeReference<>() {};
  private static final String SELECT =
      "select credential_id, partner_id, name, adapter, credential_type, vault_fields::text as vault_fields, kid,"
          + " fingerprint, subject, serial_number, not_after, version, uploaded_by, approved_by, uploaded_at,"
          + " change_request_id from credential_meta";

  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  public CredentialMetaRepository(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public Optional<CredentialMeta> find(String credentialId) {
    return jdbc.query(SELECT + " where credential_id = ?", this::row, credentialId).stream().findFirst();
  }

  public Optional<CredentialMeta> findByChangeRequest(UUID changeRequestId) {
    return jdbc.query(SELECT + " where change_request_id = ?", this::row, changeRequestId).stream().findFirst();
  }

  /** Every credential, or one partner's ({@code partnerId} non-null), by id. */
  public List<CredentialMeta> list(String partnerId) {
    return partnerId == null
        ? jdbc.query(SELECT + " order by credential_id", this::row)
        : jdbc.query(SELECT + " where partner_id = ? order by credential_id", this::row, partnerId);
  }

  /** Credentials with an expiry at or before {@code until} (expired ones too), soonest first. */
  public List<CredentialMeta> expiringBy(Instant until) {
    return jdbc.query(SELECT + " where not_after is not null and not_after <= ? order by not_after, credential_id",
        this::row, Timestamp.from(until));
  }

  /**
   * Stores {@code m} as the credential's next version (the version is the held one + 1, or 1);
   * returns it. Run inside the approving transaction.
   */
  public long upsert(CredentialMeta m) {
    Long held = jdbc.query("select version from credential_meta where credential_id = ? for update",
        (rs, i) -> rs.getLong(1), m.credentialId()).stream().findFirst().orElse(null);
    long version = held == null ? 1 : held + 1;
    Object[] values = {
      m.partnerId(), m.name(), m.adapter(), m.type().name(), json.writeValueAsString(m.vaultFields()), m.kid(),
      m.fingerprint(), m.subject(), m.serialNumber(), m.notAfter() == null ? null : Timestamp.from(m.notAfter()),
      version, m.uploadedBy(), m.approvedBy(), Timestamp.from(m.uploadedAt()), m.changeRequestId(), m.credentialId()
    };
    if (held == null) {
      jdbc.update("insert into credential_meta (partner_id, name, adapter, credential_type, vault_fields, kid,"
          + " fingerprint, subject, serial_number, not_after, version, uploaded_by, approved_by, uploaded_at,"
          + " change_request_id, credential_id) values (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", values);
    } else {
      jdbc.update("update credential_meta set partner_id = ?, name = ?, adapter = ?, credential_type = ?,"
          + " vault_fields = ?::jsonb, kid = ?, fingerprint = ?, subject = ?, serial_number = ?, not_after = ?,"
          + " version = ?, uploaded_by = ?, approved_by = ?, uploaded_at = ?, change_request_id = ?"
          + " where credential_id = ?", values);
    }
    return version;
  }

  /** Records the alert for one threshold of one credential version; false when it was raised before. */
  public boolean markAlerted(String credentialId, long version, int thresholdDays, Instant at) {
    return jdbc.update("insert into credential_alert (credential_id, version, threshold_days, raised_at)"
        + " values (?, ?, ?, ?) on conflict do nothing", credentialId, version, thresholdDays, Timestamp.from(at)) == 1;
  }

  private CredentialMeta row(ResultSet rs, int i) throws SQLException {
    Timestamp notAfter = rs.getTimestamp("not_after");
    return new CredentialMeta(
        rs.getString("credential_id"), rs.getString("partner_id"), rs.getString("name"), rs.getString("adapter"),
        CredentialType.valueOf(rs.getString("credential_type")), json.readValue(rs.getString("vault_fields"), LIST),
        rs.getString("kid"), rs.getString("fingerprint"), rs.getString("subject"), rs.getString("serial_number"),
        notAfter == null ? null : notAfter.toInstant(), rs.getLong("version"), rs.getString("uploaded_by"),
        rs.getString("approved_by"), rs.getTimestamp("uploaded_at").toInstant(),
        rs.getObject("change_request_id", UUID.class));
  }
}

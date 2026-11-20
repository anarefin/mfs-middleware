package mfs.mw.admin.api.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import mfs.mw.admin.api.client.CredentialVault;
import mfs.mw.admin.api.dto.AdminRole;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.entity.ChangeRequest;
import mfs.mw.admin.api.entity.CredentialMeta;
import mfs.mw.admin.api.exception.CredentialVaultUnavailableException;
import mfs.mw.admin.api.exception.ErrorCodes;
import mfs.mw.admin.api.exception.MwException;
import mfs.mw.admin.api.mapper.CredentialMapper;
import mfs.mw.admin.api.repository.CredentialMetaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Approving a {@code partner_credential} change request (ticket 131). Claim-first ({@link
 * ResourceChangeHandler#claimFirst()}): once a checker has claimed the request, a parallel reject or
 * expiry can no longer win, so a secret written to Vault always belongs to an APPROVED (or still
 * APPROVING) request. {@link #prepare} copies the staged fields into the partner's secret
 * ({@code payload.vaultPath} = {@code <adapter>/<partnerId>}; a patch, the partner's other fields
 * stay; idempotent, so a retry writes the same again); the decision transaction stores the metadata
 * ({@code credential_meta}, version + 1, with the change request id) and only after it commits is
 * the staged copy dropped. Settling a claim ({@link #settle}): metadata with this request's id means
 * applied; else a staged copy is written now (applied); no staged copy means it can never be applied
 * (the request expires: {@link #reusableAfterCancel} false); Vault unreachable means cannot tell.
 * Reject and expiry drop the staged copy.
 */
@Component
public class CredentialChangeHandler implements ResourceChangeHandler {

  /** {@code after.sentAs} of the approval audit. */
  static final String SENT_AS = "vault";

  private static final Logger log = LoggerFactory.getLogger(CredentialChangeHandler.class);

  private final CredentialVault vault;
  private final CredentialMetaRepository metas;
  private final TransactionTemplate tx;
  private final Clock clock;

  public CredentialChangeHandler(
      CredentialVault vault, CredentialMetaRepository metas, TransactionTemplate tx, Clock clock) {
    this.vault = vault;
    this.metas = metas;
    this.tx = tx;
    this.clock = clock;
  }

  @Override
  public String resourceType() {
    return CredentialMeta.RESOURCE_TYPE;
  }

  @Override
  public Set<AdminRole> checkers() {
    return AdminRole.CREDENTIAL_CHECKER;
  }

  @Override
  public boolean claimFirst() {
    return true;
  }

  @Override
  public boolean reusableAfterCancel(ChangeRequest cr) {
    return false;
  }

  @Override
  public Approval prepare(ChangeRequest cr, Operator checker, Instant now, String correlationId) {
    return prepare(cr, checker, now, correlationId, null);
  }

  @Override
  public Approval prepare(ChangeRequest cr, Operator checker, Instant now, String correlationId, UUID attemptId) {
    String ref = ref(cr);
    Map<String, String> fields = staged(ref).orElseThrow(() ->
        new MwException(ErrorCodes.VAL_ACTION_NOT_ALLOWED, "The uploaded credential is no longer staged; upload it again")
            .withStatus(HttpStatus.CONFLICT));
    write(cr, fields);
    return new Approval(cr.payload(), SENT_AS, () -> {
      long version = metas.upsert(CredentialMapper.fromPayload(cr.payload(), cr.makerId(), checker.username(), now, cr.id()));
      afterCommit(() -> drop(ref));
      return Map.of("version", version);
    });
  }

  @Override
  public Optional<Map<String, Object>> settle(ChangeRequest cr, UUID attemptId) {
    Optional<CredentialMeta> applied = metas.findByChangeRequest(cr.id());
    if (applied.isPresent()) {
      return Optional.of(Map.of("version", applied.get().version()));
    }
    String ref = ref(cr);
    Optional<Map<String, String>> fields = staged(ref);
    if (fields.isEmpty()) {
      return Optional.empty();
    }
    write(cr, fields.get());
    String approvedBy = cr.checkerId() == null ? "system/reconcile" : cr.checkerId();
    Long version = tx.execute(s -> metas.upsert(CredentialMapper.fromPayload(cr.payload(), cr.makerId(), approvedBy,
        clock.instant(), cr.id())));
    drop(ref);
    return Optional.of(Map.of("version", version == null ? 0L : version));
  }

  @Override
  public void closed(ChangeRequest cr, ChangeRequest.Status status, String checkerId, Instant now) {
    String ref = ref(cr);
    afterCommit(() -> drop(ref));
  }

  private Optional<Map<String, String>> staged(String ref) {
    try {
      return vault.staged(ref);
    } catch (CredentialVaultUnavailableException e) {
      throw CredentialService.unavailable(e);
    }
  }

  private void write(ChangeRequest cr, Map<String, String> fields) {
    try {
      vault.writeFields((String) cr.payload().get("vaultPath"), fields);
    } catch (CredentialVaultUnavailableException e) {
      throw CredentialService.unavailable(e);
    }
    log.atInfo().addKeyValue("event", "admin.credential.written")
        .addKeyValue("resourceId", cr.resourceId())
        .addKeyValue("changeRequestId", cr.id().toString())
        .log("credential written to Vault");
  }

  /** Best effort: a staged copy left behind holds no more than Vault already guards. */
  private void drop(String ref) {
    try {
      vault.dropStaged(ref);
    } catch (CredentialVaultUnavailableException e) {
      log.atWarn().addKeyValue("event", "admin.credential.staged_drop_failed")
          .addKeyValue("errorCode", ErrorCodes.SYS_CREDENTIAL_STORE_UNAVAILABLE)
          .log("staged credential not dropped: {}", e.getMessage());
    }
  }

  private static void afterCommit(Runnable r) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          r.run();
        }
      });
    } else {
      r.run();
    }
  }

  private static String ref(ChangeRequest cr) {
    return (String) cr.payload().get("stagedRef");
  }
}

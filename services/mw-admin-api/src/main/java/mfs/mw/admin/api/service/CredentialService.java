package mfs.mw.admin.api.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import mfs.mw.admin.api.client.CredentialVault;
import mfs.mw.admin.api.dto.AdminRole;
import mfs.mw.admin.api.dto.ChangeRequestView;
import mfs.mw.admin.api.dto.CredentialChangeBody;
import mfs.mw.admin.api.dto.CredentialView;
import mfs.mw.admin.api.dto.ErrorDetail;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.entity.CredentialMeta;
import mfs.mw.admin.api.entity.Partner;
import mfs.mw.admin.api.exception.CredentialVaultUnavailableException;
import mfs.mw.admin.api.exception.ErrorCodes;
import mfs.mw.admin.api.exception.ForbiddenException;
import mfs.mw.admin.api.exception.MwException;
import mfs.mw.admin.api.mapper.CredentialMapper;
import mfs.mw.admin.api.mapper.PartnerMapper;
import mfs.mw.admin.api.repository.CredentialMetaRepository;
import mfs.mw.admin.api.repository.PartnerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Partner credentials (ticket 131, FRS ADM-02): metadata reads, the expiry dashboard and the maker
 * side of a {@code partner_credential} change request. The upload is write-only: its secret parts
 * are checked ({@link CredentialMaterial}) and staged in Vault under a random id at once; the
 * change request stores only that id ({@code payload.stagedRef}, the sealed reference), the target
 * ({@code payload.vaultPath} = {@code <adapter>/<partnerId>}, the adapters' existing per-partner
 * secret, 131 split decision) and the metadata. A different integration checker's approval writes
 * the staged fields there ({@link CredentialChangeHandler}).
 */
@Service
public class CredentialService {

  private static final Logger log = LoggerFactory.getLogger(CredentialService.class);
  static final Pattern PARTNER_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
  static final int MAX_DAYS = 3650;

  private final PartnerRepository partners;
  private final CredentialMetaRepository metas;
  private final ChangeRequestService changeRequests;
  private final CredentialVault vault;
  private final Clock clock;
  private final SecureRandom random;

  public CredentialService(
      PartnerRepository partners, CredentialMetaRepository metas, ChangeRequestService changeRequests,
      CredentialVault vault, Clock clock, SecureRandom random) {
    this.partners = partners;
    this.metas = metas;
    this.changeRequests = changeRequests;
    this.vault = vault;
    this.clock = clock;
    this.random = random;
  }

  // --- reads ----------------------------------------------------------------------------------

  public CredentialView.Page list(Operator operator, String partnerId) {
    requireReader(operator);
    Instant now = clock.instant();
    return new CredentialView.Page(metas.list(partnerId == null ? null : checkedPartner(partnerId)).stream()
        .map(m -> CredentialMapper.view(m, now)).toList());
  }

  /** Credentials expiring within {@code days} (and expired ones), soonest first: the dashboard card. */
  public CredentialView.Page expiring(Operator operator, int days) {
    requireReader(operator);
    if (days < 1 || days > MAX_DAYS) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Invalid days")
          .withDetails(List.of(new ErrorDetail("days", "between 1 and " + MAX_DAYS)));
    }
    Instant now = clock.instant();
    return new CredentialView.Page(metas.expiringBy(now.plusSeconds(days * 86_400L)).stream()
        .map(m -> CredentialMapper.view(m, now)).toList());
  }

  // --- maker ----------------------------------------------------------------------------------

  public ChangeRequestView request(Operator maker, String partnerId, String name, CredentialChangeBody body) {
    if (!maker.hasAny(AdminRole.CREDENTIAL_MAKER)) {
      throw new ForbiddenException("Uploading a partner credential needs the integration-maker role");
    }
    Partner partner = partners.find(checkedPartner(partnerId)).orElseThrow(PartnerConfigService::notFound);
    Instant now = clock.instant();
    List<ErrorDetail> problems = new ArrayList<>();
    CredentialMaterial.Prepared prepared = CredentialMaterial.prepare(name, body, now, random, problems);
    String credentialId = CredentialMeta.id(partner.id(), name);
    Optional<CredentialMeta> held = problems.isEmpty() ? metas.find(credentialId) : Optional.empty();
    held.filter(h -> h.type() != body.type()).ifPresent(h ->
        problems.add(new ErrorDetail("type", "a credential keeps its type (upload under a new name)")));
    if (!problems.isEmpty()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "The credential is not valid").withDetails(List.copyOf(problems));
    }
    String adapter = "mw-adapter-" + partner.family();
    long next = held.map(h -> h.version() + 1).orElse(1L);
    CredentialMaterial.Meta m = prepared.meta();
    Map<String, Object> after = CredentialMapper.side(body.type(), List.copyOf(prepared.fields().keySet()),
        name + "-v" + next, m.fingerprint(), m.subject(), m.serialNumber(), m.notAfter());
    Map<String, Object> diff = PartnerMapper.diff(held.map(CredentialMapper::side).orElse(Map.of()), after);
    String ref = UUID.randomUUID().toString();
    try {
      vault.stage(ref, prepared.fields());
    } catch (CredentialVaultUnavailableException e) {
      throw unavailable(e);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("partnerId", partner.id());
    payload.put("name", name);
    payload.put("adapter", adapter);
    payload.put("vaultPath", adapter + "/" + partner.id());
    payload.put("stagedRef", ref);
    held.ifPresent(h -> payload.put("baseVersion", h.version()));
    payload.put("meta", after);
    try {
      return changeRequests.open(maker, CredentialMeta.RESOURCE_TYPE, credentialId, body.action().name(), payload, diff,
          body.reason());
    } catch (RuntimeException e) {
      dropQuietly(ref);
      throw e;
    }
  }

  private void dropQuietly(String ref) {
    try {
      vault.dropStaged(ref);
    } catch (CredentialVaultUnavailableException e) {
      log.atWarn().addKeyValue("event", "admin.credential.staged_drop_failed")
          .addKeyValue("errorCode", ErrorCodes.SYS_CREDENTIAL_STORE_UNAVAILABLE)
          .log("staged credential not dropped: {}", e.getMessage());
    }
  }

  static MwException unavailable(CredentialVaultUnavailableException e) {
    log.atWarn().addKeyValue("event", "admin.credential.store_unavailable")
        .addKeyValue("errorCode", ErrorCodes.SYS_CREDENTIAL_STORE_UNAVAILABLE)
        .log("credential store unavailable: {}", e.getMessage());
    return new MwException(ErrorCodes.SYS_CREDENTIAL_STORE_UNAVAILABLE, "The credential store is temporarily unavailable", e)
        .withStatus(HttpStatus.SERVICE_UNAVAILABLE)
        .withRetryable(true);
  }

  private static String checkedPartner(String partnerId) {
    if (partnerId == null || !PARTNER_ID.matcher(partnerId).matches()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Invalid partner id")
          .withDetails(List.of(new ErrorDetail("partnerId", "lower-case letters, digits and hyphens, at most 64")));
    }
    return partnerId;
  }

  private static void requireReader(Operator operator) {
    if (!operator.hasAny(AdminRole.READ_CREDENTIALS)) {
      throw new ForbiddenException("This operation needs a partner viewing role");
    }
  }
}

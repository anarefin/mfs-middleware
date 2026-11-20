package mfs.mw.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import mfs.mw.admin.api.client.CredentialVault;
import mfs.mw.admin.api.dto.AdminRole;
import mfs.mw.admin.api.dto.ChangeRequestView;
import mfs.mw.admin.api.dto.CredentialChangeBody;
import mfs.mw.admin.api.dto.CredentialChangeBody.CredentialAction;
import mfs.mw.admin.api.dto.CredentialType;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.dto.PartnerConfigValue;
import mfs.mw.admin.api.entity.ChangeRequest;
import mfs.mw.admin.api.entity.CredentialMeta;
import mfs.mw.admin.api.entity.Partner;
import mfs.mw.admin.api.exception.CredentialVaultUnavailableException;
import mfs.mw.admin.api.exception.ForbiddenException;
import mfs.mw.admin.api.exception.MwException;
import mfs.mw.admin.api.repository.CredentialMetaRepository;
import mfs.mw.admin.api.repository.PartnerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The partner credential change type (ticket 131): the maker's draft is checked and its secret
 * staged in Vault at once, so the change request row and its diff carry metadata only; the
 * approval (claim-first) copies the staged fields into the partner's own secret {@code
 * secret/<adapter>/<partnerId>}, stores the metadata and drops the staged copy; reject and expiry
 * drop it too. Vault here is an in-memory stand-in; {@code CredentialVaultIT} runs a real one.
 */
class CredentialChangeTest {

  static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
  static final String SECRET = "fake-api-key-0123456789abcdefXYZ";
  static final Operator MAKER = new Operator("sub-m", "int-maker01", Set.of(AdminRole.INTEGRATION_MAKER));
  static final Operator CHECKER = new Operator("sub-c", "int-checker01", Set.of(AdminRole.INTEGRATION_CHECKER));

  /** Vault as a map of path -> fields. */
  static final class FakeVault implements CredentialVault {
    final Map<String, Map<String, String>> staged = new HashMap<>();
    final Map<String, Map<String, String>> secrets = new HashMap<>();
    boolean down;

    @Override
    public void stage(String ref, Map<String, String> fields) {
      check();
      staged.put(ref, Map.copyOf(fields));
    }

    @Override
    public Optional<Map<String, String>> staged(String ref) {
      check();
      return Optional.ofNullable(staged.get(ref));
    }

    @Override
    public void dropStaged(String ref) {
      check();
      staged.remove(ref);
    }

    @Override
    public void writeFields(String path, Map<String, String> fields) {
      check();
      secrets.computeIfAbsent(path, p -> new LinkedHashMap<>()).putAll(fields);
    }

    void check() {
      if (down) {
        throw new CredentialVaultUnavailableException("down");
      }
    }
  }

  final FakeVault vault = new FakeVault();
  final PartnerRepository partners = mock(PartnerRepository.class);
  final CredentialMetaRepository metas = mock(CredentialMetaRepository.class);
  final ChangeRequestService changeRequests = mock(ChangeRequestService.class);
  final TransactionTemplate tx = mock(TransactionTemplate.class);
  final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
  CredentialService service;
  CredentialChangeHandler handler;

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    service = new CredentialService(partners, metas, changeRequests, vault, clock, new SecureRandom());
    handler = new CredentialChangeHandler(vault, metas, tx, clock);
    when(partners.find("ocs")).thenReturn(Optional.of(
        new Partner("ocs", "telco", 3, new PartnerConfigValue(null, null, null, null, null, null, null, null, null, null,
            "- route: {}", null, List.of("mw-simulators"), null), NOW, "seed", null)));
    when(metas.find(anyString())).thenReturn(Optional.empty());
    when(metas.findByChangeRequest(any())).thenReturn(Optional.empty());
    when(changeRequests.open(any(), anyString(), anyString(), anyString(), any(), any(), anyString()))
        .thenReturn(mock(ChangeRequestView.class));
  }

  static CredentialChangeBody apiKey(String secret) {
    return new CredentialChangeBody(CredentialAction.UPLOAD, "rotate the OCS key", CredentialType.API_KEY, secret,
        null, null, null, null, null);
  }

  @SuppressWarnings("unchecked")
  private ChangeRequest filed(CredentialChangeBody body) {
    service.request(MAKER, "ocs", "ocs-api-key", body);
    ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
    ArgumentCaptor<Map<String, Object>> diff = ArgumentCaptor.forClass(Map.class);
    verify(changeRequests).open(eq(MAKER), eq(CredentialMeta.RESOURCE_TYPE), eq("ocs/ocs-api-key"), eq("UPLOAD"),
        payload.capture(), diff.capture(), eq("rotate the OCS key"));
    return new ChangeRequest(UUID.randomUUID(), CredentialMeta.RESOURCE_TYPE, "ocs/ocs-api-key", "UPLOAD",
        payload.getValue(), diff.getValue(), MAKER.username(), MAKER.subject(), ChangeRequest.Status.PENDING, null, null,
        "rotate the OCS key", null, NOW, null, NOW.plusSeconds(3600));
  }

  @Test
  void theDraftIsStagedInVaultAndTheRequestRowAndDiffHoldMetadataOnly() {
    ChangeRequest cr = filed(apiKey(SECRET));
    String row = json.writeValueAsString(Map.of("payload", cr.payload(), "diff", cr.diff()));
    assertThat(row).doesNotContain(SECRET).doesNotContain(SECRET.substring(0, 16));
    assertThat(cr.payload()).containsEntry("partnerId", "ocs").containsEntry("name", "ocs-api-key")
        .containsEntry("adapter", "mw-adapter-telco").containsEntry("vaultPath", "mw-adapter-telco/ocs");
    String ref = (String) cr.payload().get("stagedRef");
    assertThat(vault.staged).containsOnlyKeys(ref);
    assertThat(vault.staged.get(ref)).containsExactly(Map.entry("ocs-api-key", SECRET));
    assertThat(cr.diff().get("after")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("kid", "ocs-api-key-v1").containsEntry("type", "API_KEY");
    assertThat(vault.secrets).isEmpty();
  }

  @Test
  void anInvalidDraftAnUnknownPartnerAndTheWrongRoleStageNothing() {
    assertThatThrownBy(() -> service.request(MAKER, "ocs", "ocs-api-key", apiKey("short")))
        .isInstanceOf(MwException.class).hasFieldOrPropertyWithValue("code", "MW-VAL-001");
    assertThatThrownBy(() -> service.request(MAKER, "nope", "ocs-api-key", apiKey(SECRET)))
        .isInstanceOf(MwException.class).hasFieldOrPropertyWithValue("code", "MW-VAL-003");
    assertThatThrownBy(() -> service.request(MAKER, "ocs", "Bad_Name", apiKey(SECRET)))
        .isInstanceOf(MwException.class).hasFieldOrPropertyWithValue("code", "MW-VAL-001");
    Operator opsMaker = new Operator("sub-o", "ops-maker01", Set.of(AdminRole.OPS_MAKER));
    assertThatThrownBy(() -> service.request(opsMaker, "ocs", "ocs-api-key", apiKey(SECRET)))
        .isInstanceOf(ForbiddenException.class);
    assertThat(vault.staged).isEmpty();
    verify(changeRequests, never()).open(any(), anyString(), anyString(), anyString(), any(), any(), anyString());
  }

  @Test
  void aCredentialKeepsItsTypeAndANewUploadIsTheNextVersion() {
    CredentialMeta held = new CredentialMeta("ocs/ocs-api-key", "ocs", "ocs-api-key", "mw-adapter-telco",
        CredentialType.HMAC_SECRET, List.of("ocs-api-key"), "ocs-api-key-v2", null, null, null, null, 2, "a", "b", NOW,
        UUID.randomUUID());
    when(metas.find("ocs/ocs-api-key")).thenReturn(Optional.of(held));
    assertThatThrownBy(() -> service.request(MAKER, "ocs", "ocs-api-key", apiKey(SECRET)))
        .isInstanceOf(MwException.class).hasFieldOrPropertyWithValue("code", "MW-VAL-001");
    assertThat(vault.staged).isEmpty();
  }

  @Test
  void aStagedCopyIsDroppedWhenTheRequestCannotBeOpened() {
    when(changeRequests.open(any(), anyString(), anyString(), anyString(), any(), any(), anyString()))
        .thenThrow(new MwException("MW-VAL-031", "open"));
    assertThatThrownBy(() -> service.request(MAKER, "ocs", "ocs-api-key", apiKey(SECRET))).isInstanceOf(MwException.class);
    assertThat(vault.staged).isEmpty();
  }

  @Test
  void vaultDownIsA503AndNothingIsFiled() {
    vault.down = true;
    assertThatThrownBy(() -> service.request(MAKER, "ocs", "ocs-api-key", apiKey(SECRET)))
        .isInstanceOf(MwException.class).hasFieldOrPropertyWithValue("code", "MW-SYS-043");
    verify(changeRequests, never()).open(any(), anyString(), anyString(), anyString(), any(), any(), anyString());
  }

  @Test
  void theApprovalWritesTheStagedFieldsIntoThePartnersSecretAndStoresTheMetadata() {
    ChangeRequest cr = filed(apiKey(SECRET));
    when(metas.upsert(any())).thenReturn(1L);
    var approval = handler.prepare(cr, CHECKER, NOW, "corr-1", UUID.randomUUID());
    assertThat(vault.secrets).containsOnlyKeys("mw-adapter-telco/ocs");
    assertThat(vault.secrets.get("mw-adapter-telco/ocs")).containsExactly(Map.entry("ocs-api-key", SECRET));
    assertThat(approval.sentAs()).isEqualTo("vault");
    assertThat(approval.apply().get()).containsEntry("version", 1L);
    ArgumentCaptor<CredentialMeta> meta = ArgumentCaptor.forClass(CredentialMeta.class);
    verify(metas).upsert(meta.capture());
    assertThat(meta.getValue().credentialId()).isEqualTo("ocs/ocs-api-key");
    assertThat(meta.getValue().uploadedBy()).isEqualTo("int-maker01");
    assertThat(meta.getValue().approvedBy()).isEqualTo("int-checker01");
    assertThat(meta.getValue().changeRequestId()).isEqualTo(cr.id());
    // no transaction synchronisation in a unit test: the staged copy is dropped at once
    assertThat(vault.staged).isEmpty();
  }

  @Test
  void aRequestWhoseStagedCopyIsGoneCannotBeAppliedAndSettlesAsNotApplied() {
    ChangeRequest cr = filed(apiKey(SECRET));
    vault.staged.clear();
    assertThatThrownBy(() -> handler.prepare(cr, CHECKER, NOW, "corr-1", UUID.randomUUID()))
        .isInstanceOf(MwException.class).hasFieldOrPropertyWithValue("code", "MW-VAL-030");
    assertThat(handler.settle(cr, UUID.randomUUID())).isEmpty();
    assertThat(handler.reusableAfterCancel(cr)).isFalse();
    assertThat(vault.secrets).isEmpty();
  }

  @Test
  void settlingAnAttemptAppliesTheStagedCopyOrReportsWhatWasAppliedOrCannotTell() {
    ChangeRequest cr = filed(apiKey(SECRET));
    when(tx.execute(any())).thenAnswer(i -> ((org.springframework.transaction.support.TransactionCallback<?>) i.getArgument(0))
        .doInTransaction(null));
    when(metas.upsert(any())).thenReturn(4L);
    assertThat(handler.settle(cr, UUID.randomUUID())).contains(Map.of("version", 4L));
    assertThat(vault.secrets.get("mw-adapter-telco/ocs")).containsEntry("ocs-api-key", SECRET);
    assertThat(vault.staged).isEmpty();
    when(metas.findByChangeRequest(cr.id())).thenReturn(Optional.of(new CredentialMeta("ocs/ocs-api-key", "ocs",
        "ocs-api-key", "mw-adapter-telco", CredentialType.API_KEY, List.of("ocs-api-key"), "ocs-api-key-v1", null, null,
        null, null, 4, "a", "b", NOW, cr.id())));
    assertThat(handler.settle(cr, UUID.randomUUID())).contains(Map.of("version", 4L));
    vault.down = true;
    when(metas.findByChangeRequest(cr.id())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> handler.settle(cr, UUID.randomUUID()))
        .isInstanceOf(MwException.class).hasFieldOrPropertyWithValue("code", "MW-SYS-043");
  }

  @Test
  void rejectionOrExpiryDropsTheStagedCopy() {
    ChangeRequest cr = filed(apiKey(SECRET));
    handler.closed(cr, ChangeRequest.Status.REJECTED, "int-checker01", NOW);
    assertThat(vault.staged).isEmpty();
    assertThat(vault.secrets).isEmpty();
  }

  @Test
  void onlyIntegrationCheckersDecideAndTheApprovalIsClaimFirst() {
    assertThat(handler.resourceType()).isEqualTo("partner_credential");
    assertThat(handler.checkers()).containsExactly(AdminRole.INTEGRATION_CHECKER);
    assertThat(handler.claimFirst()).isTrue();
  }
}

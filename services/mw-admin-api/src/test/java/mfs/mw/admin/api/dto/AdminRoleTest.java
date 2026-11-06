package mfs.mw.admin.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The spec 15.4 role names, and which of them never write or may reveal an MSISDN. */
class AdminRoleTest {

  @Test
  void theRealmRolesOfSpec154AreRecognisedAndAnythingElseIsDropped() {
    assertThat(AdminRole.values())
        .extracting(AdminRole::id)
        .containsExactly(
            "viewer", "ops-maker", "ops-checker", "ops-emergency", "integration-maker", "integration-checker",
            "recon-maker", "recon-checker", "compliance-officer", "security-admin", "auditor");
    assertThat(AdminRole.from(List.of("viewer", "ops-viewer", "default-roles-ops", "Auditor", "auditor")))
        .containsExactlyInAnyOrder(AdminRole.VIEWER, AdminRole.AUDITOR);
    assertThat(AdminRole.from(null)).isEmpty();
  }

  @Test
  void onlyComplianceOfficerAndAuditorMayRevealAndViewerAndAuditorAreReadOnly() {
    assertThat(AdminRole.REVEAL_MSISDN).containsExactlyInAnyOrder(AdminRole.COMPLIANCE_OFFICER, AdminRole.AUDITOR);
    assertThat(AdminRole.mayWrite(Set.of(AdminRole.VIEWER))).isFalse();
    assertThat(AdminRole.mayWrite(Set.of(AdminRole.AUDITOR, AdminRole.VIEWER))).isFalse();
    assertThat(AdminRole.mayWrite(Set.of())).isFalse();
    assertThat(AdminRole.mayWrite(Set.of(AdminRole.VIEWER, AdminRole.OPS_MAKER))).isTrue();
  }
}

package mfs.mw.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mfs.mw.admin.api.dto.ErrorDetail;
import mfs.mw.admin.api.dto.PartnerConfigValue;
import mfs.mw.admin.api.mapper.PartnerMapper;
import org.junit.jupiter.api.Test;

/** The partner record rules the admin checks before a change request is stored (tickets 38, 84). */
class PartnerConfigRulesTest {

  static PartnerConfigValue config(String credentialsRef, String scheme, String suffix, Long timeout, Long read) {
    return new PartnerConfigValue(
        null, null, timeout, read, null,
        scheme == null ? null : new PartnerConfigValue.Auth(scheme, credentialsRef, null),
        Map.of("1", new PartnerConfigValue.ErrorMapping(suffix, false)), null, null, null,
        "- route: {}", Map.of(), List.of("ocs.example.test"), null);
  }

  static List<String> problems(PartnerConfigValue c) {
    List<ErrorDetail> out = new ArrayList<>();
    PartnerConfigService.common(c, out);
    return out.stream().map(ErrorDetail::field).toList();
  }

  @Test
  void aCompleteRecordPasses() {
    assertThat(problems(config("api-key", "api-key", "REJECTED", 5000L, null))).isEmpty();
  }

  @Test
  void secretNamesSuffixesAuthAndTimeoutsThatTheAdaptersRefuseAreRefusedHere() {
    assertThat(problems(config("Spring.datasource.password", "api-key", "REJECTED", null, null)))
        .containsExactly("config.auth.credentialsRef");
    assertThat(problems(config(null, "hmac-sha256", "REJECTED", null, null))).containsExactly("config.auth.credentialsRef");
    assertThat(problems(config(null, "basic", "REJECTED", null, null))).containsExactly("config.auth.scheme");
    assertThat(problems(config(null, null, "lower", null, null))).containsExactly("config.errorMap");
    assertThat(problems(config(null, null, "REJECTED", 5000L, 4000L))).containsExactly("config.readTimeoutMs");
    assertThat(problems(config(null, null, "REJECTED", -1L, null))).containsExactly("config");
  }

  @Test
  void aDiffHoldsOnlyTheChangedFields() {
    Map<String, Object> diff =
        PartnerMapper.diff(Map.of("enabled", true, "timeoutMs", 20000, "route", "u"), Map.of("enabled", true, "timeoutMs", 5000, "resilience", "r"));
    assertThat(diff.get("before")).isEqualTo(Map.of("timeoutMs", 20000, "route", "u"));
    assertThat(diff.get("after")).isEqualTo(Map.of("timeoutMs", 5000, "resilience", "r"));
  }

  @Test
  void timeoutsLeftOutKeepTheirValueAndAnOperationalChangeDropsTheDryRun() {
    PartnerConfigValue c = config(null, null, "REJECTED", 20000L, 22000L)
        .withRoute("- route: {}", Map.of(), List.of("ocs.example.test"), new PartnerConfigValue.DryRun("PAY", null, null))
        .withTimeouts(null, 5000L, null);
    assertThat(c.timeoutMs()).isEqualTo(5000L);
    assertThat(c.readTimeoutMs()).isEqualTo(22000L);
    assertThat(c.withEnabled(false).isEnabled()).isFalse();
    assertThat(c.dryRun()).isNotNull();
    assertThat(c.withoutDryRun().dryRun()).isNull();
    assertThat(c.withoutDryRun().route()).isEqualTo("- route: {}");
  }

  /** The adapters' rule for a route's dry-run sandbox (ticket 83 security review), mirrored at submit. */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {
      "https://sandbox.acme.example/v1?x=1", "https://sandbox.acme.example/v1#f", "https://u:p@sandbox.acme.example",
      "ftp://sandbox.acme.example", "https://sandbox.acme.example/a/../b", "https://sandbox.acme.example/a%2Fb",
      "sandbox.acme.example"})
  void aDryRunBaseUrlMustBeAPlainAbsoluteHttpUrl(String url) {
    org.assertj.core.api.Assertions.assertThat(PartnerConfigService.plainBaseUrl(url)).as(url).isFalse();
  }

  @Test
  void aPlainSandboxUrlIsAccepted() {
    org.assertj.core.api.Assertions.assertThat(PartnerConfigService.plainBaseUrl("https://sandbox.acme.example:8443/v1")).isTrue();
  }
}

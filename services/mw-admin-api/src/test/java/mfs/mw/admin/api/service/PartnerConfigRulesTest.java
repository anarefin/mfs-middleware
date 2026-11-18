package mfs.mw.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mfs.mw.admin.api.dto.ErrorDetail;
import mfs.mw.admin.api.dto.PartnerConfigValue;
import mfs.mw.admin.api.mapper.PartnerMapper;
import org.junit.jupiter.api.Test;

/** The partner config rules the admin checks before a change request is stored (ticket 38). */
class PartnerConfigRulesTest {

  static PartnerConfigValue config(String baseUrl, String path, String credentialsRef, Long timeout, Long read) {
    return new PartnerConfigValue(
        null, null, baseUrl, null, timeout, read, null,
        credentialsRef == null ? null : new PartnerConfigValue.Auth("api-key", credentialsRef, null),
        Map.of("PAY", new PartnerConfigValue.Operation("POST", path, List.of(422), null, "{}")),
        Map.of("1", new PartnerConfigValue.ErrorMapping("REJECTED", false)), null, null, null);
  }

  static List<String> problems(PartnerConfigValue c) {
    List<ErrorDetail> out = new ArrayList<>();
    PartnerConfigService.validate(c, out);
    return out.stream().map(ErrorDetail::field).toList();
  }

  @Test
  void aCompleteConfigPasses() {
    assertThat(problems(config("https://ocs.example.test/base", "/topup/{reference}", "api-key", 5000L, null))).isEmpty();
  }

  @Test
  void urlsPathsSecretNamesAndTimeoutsThatTheAdaptersRefuseAreRefusedHere() {
    assertThat(problems(config("ftp://x.test", "/t", null, null, null))).containsExactly("config.baseUrl");
    assertThat(problems(config("https://u:p@x.test", "/t", null, null, null))).containsExactly("config.baseUrl");
    assertThat(problems(config("https://x.test?a=1", "/t", null, null, null))).containsExactly("config.baseUrl");
    assertThat(problems(config("https://x.test", "/a/../b", null, null, null))).containsExactly("config.operations.PAY.path");
    assertThat(problems(config("https://x.test", "//evil.test/a", null, null, null))).containsExactly("config.operations.PAY.path");
    assertThat(problems(config("https://x.test", "/t", "Spring.datasource.password", null, null)))
        .containsExactly("config.auth.credentialsRef");
    assertThat(problems(config("https://x.test", "/t", null, 5000L, 4000L))).containsExactly("config.readTimeoutMs");
    assertThat(problems(config("https://x.test", "/t", null, -1L, null))).containsExactly("config");
    PartnerConfigValue noRef =
        new PartnerConfigValue(null, null, "https://x.test", null, null, null, null,
            new PartnerConfigValue.Auth("hmac-sha256", null, null),
            Map.of("PAY", new PartnerConfigValue.Operation("POST", "/t", null, null, " ")),
            Map.of("1", new PartnerConfigValue.ErrorMapping("lower", false)), null, null, null);
    assertThat(problems(noRef))
        .containsExactlyInAnyOrder("config.operations.PAY.response", "config.errorMap", "config.auth.credentialsRef");
  }

  @Test
  void aDiffHoldsOnlyTheChangedFields() {
    Map<String, Object> diff =
        PartnerMapper.diff(Map.of("enabled", true, "timeoutMs", 20000, "baseUrl", "u"), Map.of("enabled", true, "timeoutMs", 5000, "resilience", "r"));
    assertThat(diff.get("before")).isEqualTo(Map.of("timeoutMs", 20000, "baseUrl", "u"));
    assertThat(diff.get("after")).isEqualTo(Map.of("timeoutMs", 5000, "resilience", "r"));
  }

  @Test
  void timeoutsLeftOutKeepTheirValue() {
    PartnerConfigValue c = config("https://x.test", "/t", null, 20000L, 22000L).withTimeouts(null, 5000L, null);
    assertThat(c.timeoutMs()).isEqualTo(5000L);
    assertThat(c.readTimeoutMs()).isEqualTo(22000L);
    assertThat(c.withEnabled(false).isEnabled()).isFalse();
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

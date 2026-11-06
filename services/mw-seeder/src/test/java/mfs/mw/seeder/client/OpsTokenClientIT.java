package mfs.mw.seeder.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import mfs.mw.seeder.config.SeederConfig;
import mfs.mw.seeder.config.SeederProperties;
import mfs.mw.seeder.exception.RemoteCallException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Ops logins with a TOTP code: Keycloak refuses a code the user already used (e.g. a second seed run,
 * or a suite, inside the same 30 s step), so a refused grant is tried once more with the next step's code.
 */
class OpsTokenClientIT {

  private static final String TOKEN = "/realms/ops/protocol/openid-connect/token";

  private final WireMockServer wm = new WireMockServer(options().dynamicPort());

  @BeforeEach
  void start() {
    wm.start();
  }

  @AfterEach
  void stop() {
    wm.stop();
  }

  /** A clock 100 ms before the next 30 s step, so the client's wait is short. */
  private static Clock justBeforeAStep() {
    long now = System.currentTimeMillis();
    long next = (now / 30_000 + 1) * 30_000;
    return Clock.offset(Clock.systemUTC(), Duration.ofMillis(next - 100 - now));
  }

  private OpsTokenClient client(Clock clock) {
    SeederProperties props =
        new SeederProperties(
            true, Duration.ofSeconds(5), Map.of(), Duration.ofMillis(5), Duration.ofMillis(5), null, null,
            new SeederProperties.Endpoints(URI.create("http://localhost:" + wm.port()), null, null),
            List.of(), null,
            Map.of("ops", new SeederProperties.Realm("fake-password", "P", "fake-secret", "S", "mw-e2e-ops", List.of())),
            List.of(), new SeederProperties.Operators("ops", "mw-e2e-ops", "m", "c"), List.of(), List.of(), null);
    return new OpsTokenClient(new HttpJson(Duration.ofSeconds(1), Duration.ofSeconds(2), SeederConfig.jsonMapper()), props, clock);
  }

  @Test
  void aRefusedCodeIsRetriedOnceWithTheNextStepsCode() {
    wm.stubFor(post(TOKEN).inScenario("t").whenScenarioStateIs(STARTED).willSetStateTo("used")
        .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"invalid_grant\"}")));
    wm.stubFor(post(TOKEN).inScenario("t").whenScenarioStateIs("used")
        .willReturn(okJson("{\"access_token\":\"t2\",\"expires_in\":300}")));

    assertThat(client(justBeforeAStep()).token("m")).isEqualTo("t2");

    var bodies = wm.findAll(postRequestedFor(urlEqualTo(TOKEN))).stream().map(r -> r.getBodyAsString()).toList();
    assertThat(bodies).hasSize(2);
    assertThat(bodies.get(0)).contains("totp=", "username=m", "client_id=mw-e2e-ops");
    assertThat(bodies.get(0)).isNotEqualTo(bodies.get(1));
  }

  @Test
  void aSecondRefusalFailsTheCall() {
    wm.stubFor(post(TOKEN).willReturn(aResponse().withStatus(401)));

    assertThatThrownBy(() -> client(justBeforeAStep()).token("m"))
        .isInstanceOfSatisfying(RemoteCallException.class, e -> assertThat(e.status()).isEqualTo(401))
        .hasMessageNotContaining("fake-password");
  }

  @Test
  void theTokenIsCachedPerUser() {
    wm.stubFor(post(TOKEN).willReturn(okJson("{\"access_token\":\"t\",\"expires_in\":300}")));
    OpsTokenClient c = client(Clock.systemUTC());

    c.token("m");
    c.token("m");

    assertThat(wm.findAll(postRequestedFor(urlEqualTo(TOKEN)))).hasSize(1);
  }
}

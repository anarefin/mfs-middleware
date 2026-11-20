package mfs.mw.adapter.remit.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import mfs.mw.adapter.remit.config.CoreClientProperties;
import mfs.mw.adapter.remit.dto.CoreAccount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** WAL-01 account read of the beneficiary lookup (ticket 119c) against a stubbed core and token endpoint. */
class CoreAccountClientTest {

  static final String ACCOUNT = "/core/v1/accounts/01711000001";

  private final WireMockServer wm = new WireMockServer(options().dynamicPort());
  private CoreAccountClient client;

  @BeforeEach
  void start() {
    wm.start();
    String base = "http://localhost:" + wm.port();
    CoreClientProperties props = new CoreClientProperties(URI.create(base), URI.create(base + "/token"),
        "mw-adapter-remit", "fake-client-secret", Duration.ofSeconds(1), Duration.ofSeconds(2));
    JsonMapper json = JsonMapper.builder().build();
    client = new CoreAccountClient(props, new ServiceTokenClient(props, json, Clock.systemUTC()), json);
    wm.stubFor(post(urlEqualTo("/token")).willReturn(aResponse().withStatus(200)
        .withHeader("Content-Type", "application/json").withBody("{\"access_token\":\"tok-1\",\"expires_in\":300}")));
  }

  @AfterEach
  void stop() {
    wm.stop();
  }

  @Test
  void anAccountIsReadWithTheServiceToken() {
    wm.stubFor(get(urlEqualTo(ACCOUNT)).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
        .withBody("{\"walletId\":\"01711000001\",\"msisdn\":\"01711000001\",\"type\":\"CUSTOMER\",\"status\":\"ACTIVE\","
            + "\"kycLevel\":\"FULL\",\"holderName\":\"Rahim Uddin\"}")));

    CoreAccount a = client.account("01711000001").orElseThrow();

    assertThat(a.type()).isEqualTo("CUSTOMER");
    assertThat(a.status()).isEqualTo("ACTIVE");
    assertThat(a.holderName()).isEqualTo("Rahim Uddin");
    wm.verify(getRequestedFor(urlEqualTo(ACCOUNT)).withHeader("Authorization", equalTo("Bearer tok-1")));
    String basic = Base64.getEncoder().encodeToString("mw-adapter-remit:fake-client-secret".getBytes(StandardCharsets.UTF_8));
    wm.verify(postRequestedFor(urlEqualTo("/token")).withHeader("Authorization", equalTo("Basic " + basic)));
  }

  @Test
  void anUnknownWalletIsEmpty() {
    wm.stubFor(get(urlEqualTo(ACCOUNT)).willReturn(aResponse().withStatus(404)
        .withBody("{\"code\":\"MW-CORE-WALLET_NOT_FOUND\"}")));

    assertThat(client.account("01711000001")).isEmpty();
  }

  @Test
  void aCoreFailureOrAnUnreadableAnswerIsUnavailable() {
    wm.stubFor(get(urlEqualTo(ACCOUNT)).willReturn(aResponse().withStatus(503)));
    assertThatThrownBy(() -> client.account("01711000001")).isInstanceOf(CoreUnavailableException.class);

    wm.stubFor(get(urlEqualTo(ACCOUNT)).willReturn(aResponse().withStatus(200).withBody("not json")));
    assertThatThrownBy(() -> client.account("01711000001")).isInstanceOf(CoreUnavailableException.class);
  }

  @Test
  void noTokenIsUnavailable() {
    wm.stubFor(post(urlEqualTo("/token")).willReturn(aResponse().withStatus(401)));

    assertThatThrownBy(() -> client.account("01711000001")).isInstanceOf(CoreUnavailableException.class);
  }
}

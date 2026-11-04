package mfs.mw.ussd.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import mfs.mw.ussd.UssdApplication;
import mfs.mw.ussd.util.PinCipher;
import mfs.mw.ussd.util.TestPinKeys;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Secrets bind from OpenBao at start-up through Spring Cloud Vault (spec 18.4): a value written
 * to {@code secret/<service>} shows up in the {@code mw.service} properties, and the core's PIN
 * public key ({@code mw.ussd.pin.*}) comes from there too.
 */
@Testcontainers(disabledWithoutDocker = true)
class VaultConfigBindingIT {

  static final String TOKEN = "test-root-token";

  @Container
  static final GenericContainer<?> OPENBAO =
      new GenericContainer<>("openbao/openbao:2.7.2")
          .withCommand("server", "-dev", "-dev-root-token-id=" + TOKEN, "-dev-listen-address=0.0.0.0:8200")
          .withExposedPorts(8200)
          .waitingFor(Wait.forHttp("/v1/sys/health").forStatusCode(200));

  String vaultUri() {
    return "http://" + OPENBAO.getHost() + ":" + OPENBAO.getMappedPort(8200);
  }

  @Test
  void secretFromOpenBaoBindsToServiceProperties() throws Exception {
    HttpResponse<String> put =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(vaultUri() + "/v1/secret/data/mw-ussd"))
                    .header("X-Vault-Token", TOKEN)
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            JsonMapper.builder().build().createObjectNode()
                                .set("data", JsonMapper.builder().build().createObjectNode()
                                    .put("mw.service.env", "from-openbao")
                                    .put("mw.ussd.pin.key-id", "core-pin-from-openbao")
                                    .put("mw.ussd.pin.public-key", TestPinKeys.pem()))
                                .toString()))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(put.statusCode()).isEqualTo(200);

    try (ConfigurableApplicationContext ctx =
        new SpringApplicationBuilder(UssdApplication.class)
            .web(WebApplicationType.NONE)
            .run(
                // No database here: this test is about config binding only.
                "--spring.flyway.enabled=false",
                "--mw.ussd.seed-menu=",
                "--spring.cloud.vault.enabled=true",
                "--spring.cloud.vault.uri=" + vaultUri(),
                "--spring.cloud.vault.authentication=TOKEN",
                "--spring.cloud.vault.token=" + TOKEN)) {
      assertThat(ctx.getBean(MwServiceProperties.class).env()).isEqualTo("from-openbao");
      assertThat(ctx.getBean(UssdProperties.class).pin().keyId()).isEqualTo("core-pin-from-openbao");
      assertThat(ctx.getBean(PinCipher.class).encrypt("4826").keyId()).isEqualTo("core-pin-from-openbao");
    }
  }
}

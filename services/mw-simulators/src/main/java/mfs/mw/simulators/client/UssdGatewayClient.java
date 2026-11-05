package mfs.mw.simulators.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import mfs.mw.simulators.dto.UssdScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The simulated MNO gateway's HTTP side (HttpUssdTransport wire format, contract
 * {@code api/mw-ussd/ussd-http.v1}): posts one step and reads the screen. Never throws.
 *
 * <p>Each MNO authenticates at the APISIX route {@code ussd-http-<mno>} with HTTP basic auth as
 * user {@code ussd-<mno>}; the password is the property {@code sim.ussd-gateway.credentials.<mno>}
 * (OpenBao {@code secret/mw-simulators} in the stack, from the generated {@code
 * USSD_<MNO>_GATEWAY_SECRET}). An MNO without one posts without credentials. Never logged.
 */
@Component
public class UssdGatewayClient {

  public static final String CREDENTIALS = "sim.ussd-gateway.credentials.";

  private static final Logger log = LoggerFactory.getLogger(UssdGatewayClient.class);

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final JsonMapper json;
  private final Environment environment;

  public UssdGatewayClient(JsonMapper json, Environment environment) {
    this.json = json;
    this.environment = environment;
  }

  public UssdScreen step(
      String mno, String url, String sessionId, String msisdn, String serviceCode, String type, String input) {
    var body = json.createObjectNode();
    body.put("sessionId", sessionId).put("msisdn", msisdn).put("type", type);
    if (serviceCode != null) {
      body.put("serviceCode", serviceCode);
    }
    body.put("input", input == null ? "" : input);
    try {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(15))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      String secret = environment.getProperty(CREDENTIALS + mno);
      if (secret != null && !secret.isEmpty()) {
        String basic = "ussd-" + mno + ":" + secret;
        request.header(
            "Authorization", "Basic " + Base64.getEncoder().encodeToString(basic.getBytes(StandardCharsets.UTF_8)));
      }
      HttpResponse<String> r = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (r.statusCode() != 200) {
        return new UssdScreen(r.statusCode(), r.body(), null);
      }
      JsonNode screen = json.readTree(r.body());
      return new UssdScreen(200, screen.path("message").asString(), screen.path("flag").asString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new UssdScreen(-1, null, null);
    } catch (Exception e) {
      log.atWarn()
          .addKeyValue("event", "sim.ussd.step.failed")
          .log("USSD step not delivered: {}", e.getClass().getSimpleName());
      return new UssdScreen(-1, null, null);
    }
  }
}

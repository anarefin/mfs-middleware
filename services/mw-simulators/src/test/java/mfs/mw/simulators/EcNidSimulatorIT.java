package mfs.mw.simulators;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import mfs.mw.simulators.contracts.OpenApiSchemas;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * EC NID mock (ticket 124b, FRS EXT-07; party {@code ec}): {@code POST /ec/v1/nid/verify} as mw-compliance's
 * {@code EcNidClient} calls it, answered from {@code seed/v1/ec/nid.json}; triggers NID ending {@code 000} not
 * found, {@code 999} timeout, {@code 111} DOB mismatch; the SOAP front end answers the same records.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestInfrastructure.class)
class EcNidSimulatorIT {

  static final String BASIC =
      "Basic " + Base64.getEncoder().encodeToString("mw-compliance:fake-ec-password".getBytes(StandardCharsets.UTF_8));

  static final String API = "api/mw-simulators/ec-nid.v1.openapi.yaml";

  private final HttpClient http = HttpClient.newHttpClient();
  private final JsonMapper json = JsonMapper.builder().build();

  @LocalServerPort int port;

  @BeforeEach
  void reset() throws Exception {
    send("DELETE", "/mock-control/ec/journal", null, null, null);
  }

  private HttpResponse<String> verify(String nid, String dob) throws Exception {
    return send("POST", "/ec/v1/nid/verify", "application/json", BASIC,
        "{\"nid\":\"%s\",\"dob\":\"%s\",\"consentId\":\"c-1\",\"requestId\":\"r-1\"}".formatted(nid, dob));
  }

  @Test
  void aSeededNidAnswersTheCitizensRecord() throws Exception {
    HttpResponse<String> res = verify("1990123456789", "1990-05-17");

    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    JsonNode p = json.readTree(res.body());
    assertThat(p.path("nid").asString()).isEqualTo("1990123456789");
    assertThat(p.path("dob").asString()).isEqualTo("1990-05-17");
    assertThat(p.path("nameEn").asString()).isEqualTo("MD RAHIM UDDIN");
    assertThat(p.path("nameBn").asString()).isEqualTo("মোঃ রহিম উদ্দিন");
    assertThat(p.path("fatherName").asString()).isNotBlank();
    assertThat(p.path("motherName").asString()).isNotBlank();
    assertThat(p.path("address").asString()).isNotBlank();
    assertThat(p.path("photo").asString()).startsWith("placeholder:");
    assertThat(OpenApiSchemas.response(API, "/ec/v1/nid/verify", "post", "200").validate(p)).isEmpty();
  }

  @Test
  void anUnknownNidOrOneEndingIn000IsNotFound() throws Exception {
    assertThat(verify("1990000000001", "1990-05-17").statusCode()).isEqualTo(404);
    HttpResponse<String> trigger = verify("1990123456000", "1990-05-17");
    assertThat(trigger.statusCode()).isEqualTo(404);
    assertThat(json.readTree(trigger.body()).path("code").asString()).isEqualTo("NID_NOT_FOUND");
    assertThat(OpenApiSchemas.response(API, "/ec/v1/nid/verify", "post", "404").validate(json.readTree(trigger.body())))
        .isEmpty();
  }

  @Test
  void anNidEndingIn111AnswersAnotherDateOfBirth() throws Exception {
    HttpResponse<String> res = verify("1985123456111", "1985-02-03");
    assertThat(res.statusCode()).isEqualTo(200);
    assertThat(json.readTree(res.body()).path("dob").asString()).isEqualTo("1986-02-03");
  }

  @Test
  void anNidEndingIn999IsHeldPastTheCallersTimeout() throws Exception {
    send("PUT", "/mock-control/ec/settings", "application/json", null, "{\"timeoutDelayMs\":400}");
    long start = System.nanoTime();
    HttpResponse<String> res = verify("1985123456999", "1985-02-03");
    long ms = (System.nanoTime() - start) / 1_000_000;
    assertThat(ms).isGreaterThanOrEqualTo(400);
    assertThat(res.statusCode()).isEqualTo(200);
  }

  @Test
  void noCredentialsIs401() throws Exception {
    HttpResponse<String> res = send("POST", "/ec/v1/nid/verify", "application/json", null,
        "{\"nid\":\"1990123456789\",\"dob\":\"1990-05-17\"}");
    assertThat(res.statusCode()).isEqualTo(401);
  }

  @Test
  void mockControlTakesTheEcDown() throws Exception {
    send("POST", "/mock-control/ec/scenarios", "application/json", null,
        "{\"operation\":\"verify\",\"behaviour\":\"HTTP_5XX\",\"httpStatus\":503,\"nextN\":1}");
    assertThat(verify("1990123456789", "1990-05-17").statusCode()).isEqualTo(503);
    assertThat(verify("1990123456789", "1990-05-17").statusCode()).isEqualTo(200);
  }

  @Test
  void theSoapFrontEndAnswersTheSameRecord() throws Exception {
    String envelope = """
        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:ec="urn:ec:nid:v1">
          <soapenv:Body><ec:VerifyNid><ec:nid>1990123456789</ec:nid><ec:dob>1990-05-17</ec:dob></ec:VerifyNid></soapenv:Body>
        </soapenv:Envelope>""";
    HttpResponse<String> res = send("POST", "/ec/v1/soap", "text/xml; charset=utf-8", BASIC, envelope);
    assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    assertThat(res.body()).contains("VerifyNidResponse").contains("<ec:nameEn>MD RAHIM UDDIN</ec:nameEn>")
        .contains("<ec:dob>1990-05-17</ec:dob>");

    String unknown = envelope.replace("1990123456789", "1990123456000");
    HttpResponse<String> fault = send("POST", "/ec/v1/soap", "text/xml; charset=utf-8", BASIC, unknown);
    assertThat(fault.statusCode()).isEqualTo(500);
    assertThat(fault.body()).contains("Fault").contains("NID_NOT_FOUND");
  }

  private HttpResponse<String> send(String method, String path, String type, String auth, String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (auth != null) {
      b.header("Authorization", auth);
    }
    if (body == null) {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      b.header("Content-Type", type).method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }
}

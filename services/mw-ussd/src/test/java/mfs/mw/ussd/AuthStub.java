package mfs.mw.ussd;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.util.List;
import mfs.mw.ussd.util.TestPinKeys;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * mw-auth for the tests that start the application (ticket 72): one plain-HTTP WireMock per test JVM
 * (mTLS is off in tests, {@code client/AuthClientIT} proves it) serving the service token, the PIN
 * transport key ({@link TestPinKeys}, generated per run) and a fixed assertion for every
 * transaction authorisation. A test can make the next authorisations answer an error envelope.
 */
public final class AuthStub {

  public static final String KEY_ID = "PIN_TRANSPORT.v1";
  /** A compact JWS shape (not a real signature: the orchestrator stub checks nothing). */
  public static final String ASSERTION = "eyJhbGciOiJFUzI1NiIsInR5cCI6InR4bi1hc3NlcnRpb24rand0In0.eyJqdGkiOiJ0In0.c2lnbmF0dXJl";
  static final String TXN_AUTH = "/internal/v1/txn-authorisations";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static WireMockServer server;

  private AuthStub() {}

  /** Starts the stub once and (re)sets the default answers. */
  public static synchronized WireMockServer start() {
    if (server == null) {
      server = new WireMockServer(options().dynamicPort());
      server.start();
    }
    if (server.listAllStubMappings().getMappings().isEmpty()) {
      defaults();
    }
    return server;
  }

  /** Back to the default answers (a valid assertion for every authorisation). */
  public static synchronized void defaults() {
    server.resetMappings();
    server.stubFor(post(urlEqualTo("/token")).willReturn(okJson("{\"access_token\":\"tok\",\"expires_in\":300}")));
    server.stubFor(get(urlEqualTo("/internal/v1/pin-key")).willReturn(okJson(
        JSON.writeValueAsString(java.util.Map.of(
            "keyId", KEY_ID, "algorithm", "RSA-OAEP-SHA-256", "publicKeyPem", TestPinKeys.pem(),
            "purpose", "PIN_TRANSPORT")))));
    server.stubFor(post(urlEqualTo(TXN_AUTH)).willReturn(okJson(
        "{\"assertion\":\"" + ASSERTION + "\",\"expiresAt\":\"2030-01-01T00:00:00Z\",\"newDevice\":false}")));
  }

  /** The next authorisations answer {@code status} with an error envelope of {@code code}. */
  public static synchronized void refuse(int status, String code, String detailField, String detailIssue) {
    String details = detailField == null ? "[]" : "[{\"field\":\"" + detailField + "\",\"issue\":\"" + detailIssue + "\"}]";
    server.stubFor(post(urlEqualTo(TXN_AUTH)).willReturn(aResponse()
        .withStatus(status)
        .withHeader("Content-Type", "application/json")
        .withBody("{\"code\":\"" + code + "\",\"message\":\"refused\",\"correlationId\":\"c\",\"retryable\":false,"
            + "\"details\":" + details + "}")));
  }

  public static String baseUrl() {
    return "http://localhost:" + start().port();
  }

  /** Every authorisation body received so far, oldest first. */
  public static List<JsonNode> authorisations() {
    return start().findAll(postRequestedFor(urlEqualTo(TXN_AUTH))).stream()
        .map(LoggedRequest::getBodyAsString)
        .map(JSON::readTree)
        .toList();
  }
}

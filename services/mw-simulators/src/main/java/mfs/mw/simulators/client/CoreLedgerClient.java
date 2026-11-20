package mfs.mw.simulators.client;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import mfs.mw.simulators.config.FileMockProperties;
import mfs.mw.simulators.dto.LedgerEntry;
import mfs.mw.simulators.exception.ErrorCodes;
import mfs.mw.simulators.exception.MwException;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The trust bank mock's view of mw-core-mock's ledger (ticket 123, FRS EXT-06), through its REST API only (never
 * its schema): {@code GET /core/v1/statements} (contract {@code api/mw-core-mock/core-wallet.v1}, every page,
 * with a realm services client-credentials token when {@code sim.files.trustbank.core.token-uri} is set) and
 * {@code GET /core-mock/v1/wallets/{walletId}} (contract {@code api/mw-core-mock/core-mock-seed.v1}, the balance of
 * a day without entries). A failure is 503 {@code MW-SYS-FILE_MOCK_UNAVAILABLE}.
 */
@Component
@Profile(MockControl.PROFILE)
public class CoreLedgerClient {

  private static final int PAGE = 500;

  private final FileMockProperties.Core props;
  private final JsonMapper json;
  private final Clock clock;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private String token;
  private Instant tokenExpiry = Instant.EPOCH;

  public CoreLedgerClient(FileMockProperties props, JsonMapper json, Clock clock) {
    this.props = props.trustbank().core();
    this.json = json;
    this.clock = clock;
  }

  /** Every entry of {@code walletId} in {@code [from, to)}, oldest first. */
  public List<LedgerEntry> entries(String walletId, Instant from, Instant to) {
    List<LedgerEntry> out = new ArrayList<>();
    for (int page = 0; ; page++) {
      String uri = props.baseUrl() + "/core/v1/statements?walletId=" + enc(walletId) + "&from=" + enc(from.toString())
          + "&to=" + enc(to.toString()) + "&limit=" + PAGE + "&page=" + page;
      JsonNode body = get(uri, true);
      for (JsonNode e : body.path("entries")) {
        JsonNode after = e.path("balanceAfter");
        out.add(new LedgerEntry(e.path("txnId").asString(), e.path("direction").asString(), e.path("kind").asString(),
            e.path("amount").path("valueMinor").asLong(),
            after.isMissingNode() || after.isNull() ? null : after.path("valueMinor").asLong(),
            Instant.parse(e.path("at").asString())));
      }
      if (!body.path("hasMore").asBoolean(false)) {
        return out;
      }
    }
  }

  /** The wallet's balance now (poisha). */
  public long balanceMinor(String walletId) {
    return get(props.baseUrl() + "/core-mock/v1/wallets/" + enc(walletId), false).path("balanceMinor").asLong();
  }

  private JsonNode get(String uri, boolean authorised) {
    try {
      HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(uri)).timeout(props.timeout()).GET()
          .header("Accept", "application/json");
      if (authorised && !props.tokenUri().isBlank()) {
        b.header("Authorization", "Bearer " + token());
      }
      HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() != 200) {
        throw unavailable("mw-core-mock answered " + res.statusCode(), null);
      }
      return json.readTree(res.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unavailable("interrupted", e);
    } catch (MwException e) {
      throw e;
    } catch (Exception e) {
      throw unavailable("mw-core-mock unreachable: " + e.getClass().getSimpleName(), e);
    }
  }

  private synchronized String token() throws Exception {
    if (token != null && Instant.now(clock).isBefore(tokenExpiry)) {
      return token;
    }
    String form = "grant_type=client_credentials&client_id=" + enc(props.clientId()) + "&client_secret="
        + enc(props.clientSecret());
    HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(props.tokenUri())).timeout(props.timeout())
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    if (res.statusCode() != 200) {
      throw unavailable("token endpoint answered " + res.statusCode(), null);
    }
    JsonNode t = json.readTree(res.body());
    token = t.path("access_token").asString();
    tokenExpiry = Instant.now(clock).plusSeconds(Math.max(0, t.path("expires_in").asLong(60) - 30));
    return token;
  }

  private static MwException unavailable(String message, Throwable cause) {
    return new MwException(ErrorCodes.SYS_FILE_MOCK_UNAVAILABLE, message, cause)
        .withStatus(HttpStatus.SERVICE_UNAVAILABLE).withRetryable(true);
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}

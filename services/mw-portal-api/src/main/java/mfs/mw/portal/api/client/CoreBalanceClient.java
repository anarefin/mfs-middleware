package mfs.mw.portal.api.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import mfs.mw.portal.api.config.CoreClientProperties;
import mfs.mw.portal.api.dto.Amount;
import mfs.mw.portal.api.exception.CoreUnavailableException;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The core wallet API's WAL-03 balance read of an org's payout wallet ({@code GET
 * /core/v1/balance/{walletId}}, contract {@code api/mw-core-mock/core-wallet.v1}, ticket 129c), a
 * plain read the core allows this client for ORG wallets only. Authenticated with this service's
 * client-credentials token ({@link ServiceTokenClient}); a 401 drops the cached token. Anything but
 * a readable 200 is {@link CoreUnavailableException}: the caller never guesses a balance.
 */
@Component
public class CoreBalanceClient {

  static final String PATH = "/core/v1/balance/";

  private final CoreClientProperties props;
  private final JsonMapper json;
  private final HttpClient http;
  private final ServiceTokenClient tokens;

  public CoreBalanceClient(CoreClientProperties props, JsonMapper json, ServiceTokenClient tokens) {
    this.props = props;
    this.json = json;
    this.tokens = tokens;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(props.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  /** The wallet's available balance (total minus held) in poisha. */
  public long available(String walletId) {
    String base = props.baseUrl().toString().replaceAll("/+$", "");
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(base + PATH + URLEncoder.encode(walletId, StandardCharsets.UTF_8)))
            .timeout(props.readTimeout())
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + tokens.token())
            .GET();
    String correlationId = MDC.get("correlationId");
    if (correlationId != null) {
      b.header("X-Correlation-Id", correlationId);
    }
    HttpResponse<byte[]> res;
    try {
      res = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw new CoreUnavailableException("core not reachable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CoreUnavailableException("interrupted", e);
    }
    if (res.statusCode() == 401) {
      tokens.invalidate();
    }
    if (res.statusCode() != 200) {
      throw new CoreUnavailableException("core balance read answered " + res.statusCode(), null);
    }
    try {
      Balance balance = json.readValue(res.body(), Balance.class);
      if (balance.available() == null) {
        throw new CoreUnavailableException("core balance without an available amount", null);
      }
      return balance.available().valueMinor();
    } catch (JacksonException e) {
      throw new CoreUnavailableException("core balance unreadable", e);
    }
  }

  /** The part of the core's Balance this service reads. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Balance(Amount available) {}
}

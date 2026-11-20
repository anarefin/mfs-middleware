package mfs.mw.ussd.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import mfs.mw.ussd.config.CoreClientProperties;
import mfs.mw.ussd.exception.CoreUnavailableException;
import mfs.mw.ussd.service.AccountReads;
import mfs.mw.ussd.service.AccountScreens;
import mfs.mw.ussd.service.ChargeQuote;
import mfs.mw.ussd.util.UssdText;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Core reads for a USSD screen, with this service's client-credentials JWT (audience
 * {@code mw-core-mock}): WAL-04 {@code POST /core/v1/charges/calculate}, WAL-12
 * {@code GET /core/v1/statements} (the latest five movements in 30 days) and WAL-13
 * {@code GET /core/v1/transactions/{txnId}} for a remittance reference that is an Upay txn id.
 * Anything that is not a readable answer fails closed. No MSISDN or reference is logged.
 */
@Component
@ConditionalOnProperty(name = "mw.ussd.core.mode", havingValue = "http", matchIfMissing = true)
public class CoreWalletClient implements ChargeQuote, AccountReads {

  private static final Pattern TXN_ID =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
  private static final Set<String> MOVEMENTS = Set.of("TRANSFER", "REVERSAL", "ADJUSTMENT");
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM").withZone(ZoneOffset.UTC);
  private static final int STATEMENT_DAYS = 30;
  private static final int PAGE = 100;
  /** The statement contract's last page index ({@code page} maximum 100000). */
  private static final int PAGE_LAST = 100_000;
  private static final int LINES = 5;

  private final CoreClientProperties props;
  private final ServiceTokenClient tokens;
  private final JsonMapper json;
  private final Clock clock;
  private final HttpClient http;

  public CoreWalletClient(
      CoreClientProperties props, ServiceTokenClient tokens, JsonMapper json, Clock clock) {
    this.props = props;
    this.tokens = tokens;
    this.json = json;
    this.clock = clock;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(props.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @Override
  @CircuitBreaker(name = "core")
  public Quoted quote(String txnType, long amountMinor, String customerType) {
    ObjectNode body = json.createObjectNode();
    body.put("txnType", txnType);
    ObjectNode amount = body.putObject("amount");
    amount.put("valueMinor", amountMinor);
    amount.put("currency", "BDT");
    body.put("channel", "USSD");
    body.put("customerType", customerType == null || customerType.isBlank() ? "CUSTOMER" : customerType);
    JsonNode answered = post("/core/v1/charges/calculate", body);
    long fee = answered.path("fee").path("valueMinor").asLong(-1);
    long total = answered.path("total").path("valueMinor").asLong(-1);
    if (fee < 0 || total < 0) {
      throw new CoreUnavailableException("core charges unreadable", null);
    }
    return new Quoted(fee, total);
  }

  @Override
  @CircuitBreaker(name = "core")
  public String statement(String walletId, String language) {
    Instant to = clock.instant();
    Instant from = to.minus(Duration.ofDays(STATEMENT_DAYS));
    // Pages are oldest-first. Keep walking while hasMore, and keep only the latest five movements.
    ArrayDeque<String> latest = new ArrayDeque<>(LINES);
    for (int page = 0; page <= PAGE_LAST; page++) {
      String query =
          "?walletId=" + enc(walletId)
              + "&from=" + enc(from.toString())
              + "&to=" + enc(to.toString())
              + "&limit=" + PAGE
              + "&page=" + page;
      JsonNode body = get("/core/v1/statements" + query);
      for (JsonNode entry : body.path("entries")) {
        if (MOVEMENTS.contains(entry.path("kind").asString())) {
          latest.addLast(line(entry));
          if (latest.size() > LINES) {
            latest.removeFirst();
          }
        }
      }
      if (!body.path("hasMore").asBoolean(false)) {
        break;
      }
    }
    List<String> newestFirst = new ArrayList<>(latest);
    Collections.reverse(newestFirst);
    return AccountScreens.statement(language, newestFirst);
  }

  @Override
  @CircuitBreaker(name = "core")
  public String remitStatus(String reference, String language) {
    if (reference == null || !TXN_ID.matcher(reference).matches()) {
      return AccountScreens.noRemit(language);
    }
    JsonNode body = get("/core/v1/transactions/" + enc(reference));
    String status = body.path("status").asString("");
    if (status.isBlank() || "NOT_FOUND".equals(status)) {
      return AccountScreens.noRemit(language);
    }
    return AccountScreens.remit(language, status);
  }

  private static String line(JsonNode entry) {
    String when = entry.path("at").asString("");
    String day;
    try {
      day = DAY.format(Instant.parse(when));
    } catch (RuntimeException e) {
      day = "--/--";
    }
    String side = "CREDIT".equals(entry.path("direction").asString()) ? "Cr" : "Dr";
    long minor = entry.path("amount").path("valueMinor").asLong(0);
    return day + " " + side + " " + UssdText.taka(minor);
  }

  private JsonNode post(String path, ObjectNode body) {
    HttpRequest req =
        HttpRequest.newBuilder(uri(path))
            .timeout(props.readTimeout())
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + tokens.token())
            .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)))
            .build();
    return send(req, "core charges");
  }

  private JsonNode get(String pathAndQuery) {
    HttpRequest req =
        HttpRequest.newBuilder(uri(pathAndQuery))
            .timeout(props.readTimeout())
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + tokens.token())
            .GET()
            .build();
    return send(req, "core read");
  }

  private JsonNode send(HttpRequest req, String what) {
    HttpResponse<byte[]> res;
    try {
      res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw new CoreUnavailableException(what + " not reachable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CoreUnavailableException(what + " interrupted", e);
    }
    if (res.statusCode() < 200 || res.statusCode() >= 300) {
      throw new CoreUnavailableException(what + " answered " + res.statusCode(), null);
    }
    try {
      return json.readTree(res.body() == null ? new byte[0] : res.body());
    } catch (RuntimeException e) {
      throw new CoreUnavailableException(what + " unreadable", e);
    }
  }

  private URI uri(String pathAndQuery) {
    return URI.create(props.baseUrl().toString().replaceAll("/+$", "") + pathAndQuery);
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}

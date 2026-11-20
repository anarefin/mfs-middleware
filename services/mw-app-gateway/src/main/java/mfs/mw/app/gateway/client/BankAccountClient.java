package mfs.mw.app.gateway.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import mfs.mw.app.gateway.config.BankClientProperties;
import mfs.mw.app.gateway.dto.BankAccountValidation;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * mw-adapter-bank's account validation (ticket 117b, contract {@code
 * api/mw-adapter-bank/bank.v1.openapi.yaml}): {@code POST /bank/v1/accounts/validate}. Authenticated
 * with this service's client-credentials token ({@link ServiceTokenClient}, audience {@code
 * mw-adapter-bank}); a 401 drops the cached token and asks once more. Not retried otherwise: every
 * call is a partner INQUIRY. The adapter's envelope (400, 502 {@code MW-PTR-*}, 503 {@code
 * MW-SYS-PARTNER_*}, 504 {@code MW-TMO-*}) passes through with its code, status and retryable flag;
 * the adapter out of reach, unreadable or refusing this service is 503 {@code MW-SYS-016}. Runs on
 * the HTTP request's (virtual) thread. Nothing sent or received is logged.
 */
@Component
public class BankAccountClient {

  static final String PATH = "/bank/v1/accounts/validate";

  private final BankClientProperties props;
  private final ServiceTokenClient tokens;
  private final JsonMapper json;
  private final HttpClient http;

  public BankAccountClient(BankClientProperties props, ServiceTokenClient tokens, JsonMapper json) {
    this.props = props;
    this.tokens = tokens;
    this.json = json;
    this.http = HttpClient.newBuilder()
        .connectTimeout(props.connectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  public BankAccountValidation validate(String bankCode, String accountNumber) {
    return validate(bankCode, accountNumber, null);
  }

  /**
   * As above, with the wallet's KYC name as {@code matchName} (M9 review owner binding): the adapter
   * compares it with the bank's whole holder name and answers {@code nameMatch}. Never logged.
   */
  public BankAccountValidation validate(String bankCode, String accountNumber, String matchName) {
    Map<String, String> fields = new java.util.LinkedHashMap<>();
    fields.put("bankCode", bankCode);
    fields.put("accountNumber", accountNumber);
    if (matchName != null) {
      fields.put("matchName", matchName);
    }
    byte[] body = json.writeValueAsBytes(fields);
    HttpResponse<byte[]> res = exchange(body);
    if (res.statusCode() == 401) {
      tokens.invalidate();
      res = exchange(body);
    }
    byte[] raw = res.body() == null ? new byte[0] : res.body();
    int status = res.statusCode();
    if (status == 200) {
      try {
        BankAccountValidation v = json.readValue(raw, BankAccountValidation.class);
        if (v.accountLast4() == null || v.holderName() == null) {
          throw unavailable(null);
        }
        return v;
      } catch (JacksonException e) {
        throw unavailable(e);
      }
    }
    if (status == 400 || status == 502 || status == 503 || status == 504) {
      MwException passed = envelope(raw, status);
      if (passed != null) {
        throw passed;
      }
    }
    throw unavailable(null);
  }

  private MwException envelope(byte[] raw, int status) {
    try {
      JsonNode tree = json.readTree(raw);
      String code = tree.path("code").asString(null);
      if (code == null || !code.startsWith("MW-")) {
        return null;
      }
      String message = tree.path("message").asString("Bank account validation failed");
      MwException e = new MwException(code, message)
          .withStatus(HttpStatus.valueOf(status))
          .withRetryable(tree.path("retryable").asBoolean(false));
      String bn = tree.path("messageBn").asString(null);
      return bn == null ? e : e.withMessageBn(bn);
    } catch (JacksonException e) {
      return null;
    }
  }

  private HttpResponse<byte[]> exchange(byte[] body) {
    String token;
    try {
      token = tokens.token();
    } catch (MwException e) {
      throw unavailable(e);
    }
    String base = props.baseUrl().toString().replaceAll("/+$", "");
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + PATH))
        .timeout(props.readTimeout())
        .header("Accept", "application/json")
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + token)
        .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    String correlationId = MDC.get("correlationId");
    if (correlationId != null) {
      b.header("X-Correlation-Id", correlationId);
    }
    try {
      return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw unavailable(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unavailable(e);
    }
  }

  private static MwException unavailable(Throwable cause) {
    return new MwException(ErrorCodes.SYS_BANK_UNAVAILABLE, "Bank account validation is unavailable, try again", cause)
        .withStatus(HttpStatus.SERVICE_UNAVAILABLE)
        .withRetryable(true);
  }
}

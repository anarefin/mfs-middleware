package mfs.mw.simulators.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Posts simulator callbacks to the URL a test configured. Never throws: -1 means unreachable. */
@Component
public class CallbackClient {

  private static final Logger log = LoggerFactory.getLogger(CallbackClient.class);

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  public int post(String url, String json) {
    try {
      HttpRequest req =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(5))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json))
              .build();
      return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return -1;
    } catch (Exception e) {
      log.atWarn()
          .addKeyValue("event", "sim.callback.failed")
          .log("callback not delivered: {}", e.getClass().getSimpleName());
      return -1;
    }
  }
}

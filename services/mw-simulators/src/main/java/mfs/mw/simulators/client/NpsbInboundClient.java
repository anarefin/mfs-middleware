package mfs.mw.simulators.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Posts the NPSB mock's inbound pacs.008 (ticket 121b part 2) to the middleware as the switch would:
 * {@code application/xml}, the raw document; answers the status and the body (the adapter's pacs.002 or
 * its error envelope). Status -1 when nothing answered.
 */
@Component
public class NpsbInboundClient {

  /** What the middleware answered. */
  public record Answer(int status, String body) {}

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public Answer post(String url, String xml) {
    HttpRequest req = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(15))
        .header("Content-Type", "application/xml")
        .POST(HttpRequest.BodyPublishers.ofString(xml, StandardCharsets.UTF_8))
        .build();
    try {
      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return new Answer(res.statusCode(), res.body());
    } catch (IOException e) {
      return new Answer(-1, null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Answer(-1, null);
    }
  }
}

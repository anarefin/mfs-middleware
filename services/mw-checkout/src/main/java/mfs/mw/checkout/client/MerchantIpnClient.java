package mfs.mw.checkout.client;

import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import mfs.mw.checkout.config.IpnProperties;
import org.springframework.stereotype.Component;

/**
 * Posts one IPN to a merchant (spec 9.3 step 3, ticket 32). One attempt per call, never retried
 * here (the schedule lives in {@code checkout.ipn_delivery}); bounded by the Resilience4j time
 * limiter {@value #INSTANCE} (default 10 s) and the connect timeout. Redirects are not followed, so
 * a checked URL cannot bounce the call elsewhere. Never throws: the result says what happened.
 */
@Component
public class MerchantIpnClient {

  public static final String INSTANCE = "merchant-ipn";

  /** HTTP status of the answer (null when there was none) or an error code, and the duration. */
  public record Result(Integer httpStatus, String error, long durationMs) {

    public boolean delivered() {
      return httpStatus != null && httpStatus >= 200 && httpStatus < 300;
    }
  }

  private final HttpClient http;
  private final TimeLimiter limiter;

  public MerchantIpnClient(TimeLimiterRegistry limiters, IpnProperties props) {
    this.limiter = limiters.timeLimiter(INSTANCE);
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(props.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  public Result post(URI url, Map<String, String> headers, String body) {
    Duration timeout = limiter.getTimeLimiterConfig().getTimeoutDuration();
    HttpRequest.Builder b =
        HttpRequest.newBuilder(url)
            .timeout(timeout.plusSeconds(1))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    headers.forEach(b::header);
    HttpRequest request = b.build();
    long start = System.nanoTime();
    try {
      HttpResponse<Void> r =
          limiter.executeFutureSupplier(() -> http.sendAsync(request, HttpResponse.BodyHandlers.discarding()));
      return new Result(r.statusCode(), null, millisSince(start));
    } catch (TimeoutException e) {
      return new Result(null, "TIMEOUT", millisSince(start));
    } catch (ExecutionException e) {
      return new Result(null, errorOf(e.getCause()), millisSince(start));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Result(null, "INTERRUPTED", millisSince(start));
    } catch (Exception e) {
      return new Result(null, errorOf(e), millisSince(start));
    }
  }

  private static String errorOf(Throwable t) {
    if (t instanceof HttpConnectTimeoutException) {
      return "CONNECT_TIMEOUT";
    }
    if (t instanceof java.net.http.HttpTimeoutException) {
      return "TIMEOUT";
    }
    if (t instanceof ConnectException) {
      return "CONNECT_FAILED";
    }
    return "IO_ERROR";
  }

  private static long millisSince(long start) {
    return Duration.ofNanos(System.nanoTime() - start).toMillis();
  }
}

package mfs.mw.checkout.client;

import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import mfs.mw.checkout.config.IpnProperties;
import mfs.mw.checkout.security.IpnUrlPolicy;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

/**
 * Posts one IPN to a merchant (spec 9.3 step 3, ticket 32). One attempt per call, never retried
 * here (the schedule lives in {@code checkout.ipn_delivery}); bounded by the Resilience4j time
 * limiter {@value #INSTANCE} (default 10 s) and the connect timeout. Never throws: the result says
 * what happened.
 *
 * <p>SSRF (review A7): the call connects only to the addresses {@link IpnUrlPolicy#lookup} resolved
 * and checked (the client's DNS answers the URL's host with exactly those, and nothing else), so a
 * name re-pointed between the check and the connect (DNS rebinding) cannot reach inside. The URL
 * keeps the merchant's name: it is the Host header, the TLS SNI name and the name the certificate
 * is verified against (Apache HttpClient's default hostname verifier). Redirects are not followed,
 * so a checked URL cannot bounce the call elsewhere; no proxy, cookies or automatic retries. A new
 * client per call, so no connection outlives its check.
 */
@Component
public class MerchantIpnClient {

  public static final String INSTANCE = "merchant-ipn";

  private static final ContentType JSON = ContentType.create("application/json");

  /** HTTP status of the answer (null when there was none) or an error code, and the duration. */
  public record Result(Integer httpStatus, String error, long durationMs) {

    public boolean delivered() {
      return httpStatus != null && httpStatus >= 200 && httpStatus < 300;
    }
  }

  private final TimeLimiter limiter;
  private final Duration connectTimeout;
  private final SSLContext tls;
  private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();

  public MerchantIpnClient(TimeLimiterRegistry limiters, IpnProperties props, SSLContext merchantIpnTls) {
    this.limiter = limiters.timeLimiter(INSTANCE);
    this.connectTimeout = props.connectTimeout();
    this.tls = merchantIpnTls;
  }

  /** Posts {@code body} to the checked target's URL, connecting to its checked addresses only. */
  public Result post(IpnUrlPolicy.Decision target, Map<String, String> headers, String body) {
    if (target.refused() || target.uri() == null || target.addresses().isEmpty()) {
      return new Result(null, "URL_REFUSED: not checked", 0);
    }
    Duration timeout = limiter.getTimeLimiterConfig().getTimeoutDuration();
    long start = System.nanoTime();
    CloseableHttpClient http = clientFor(target, timeout);
    try {
      HttpPost post = new HttpPost(target.uri());
      headers.forEach(post::setHeader);
      post.setEntity(new ByteArrayEntity(body.getBytes(StandardCharsets.UTF_8), JSON));
      Integer status = limiter.executeFutureSupplier(
          () -> CompletableFuture.supplyAsync(() -> execute(http, post), calls));
      return new Result(status, null, millisSince(start));
    } catch (TimeoutException e) {
      return new Result(null, "TIMEOUT", millisSince(start));
    } catch (ExecutionException e) {
      return new Result(null, errorOf(e.getCause()), millisSince(start));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Result(null, "INTERRUPTED", millisSince(start));
    } catch (Exception e) {
      return new Result(null, errorOf(e), millisSince(start));
    } finally {
      http.close(CloseMode.IMMEDIATE); // also aborts a call still running after a timeout
    }
  }

  private static int execute(CloseableHttpClient http, HttpPost post) {
    try {
      return http.execute(post, response -> response.getCode());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private CloseableHttpClient clientFor(IpnUrlPolicy.Decision target, Duration timeout) {
    Timeout limit = Timeout.of(timeout.plusSeconds(1));
    return HttpClients.custom()
        .setConnectionManager(
            PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new PinnedDns(target.uri().getHost(), target.addresses()))
                .setTlsSocketStrategy(new DefaultClientTlsStrategy(tls))
                .setDefaultConnectionConfig(
                    ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(connectTimeout))
                        .setSocketTimeout(limit)
                        .build())
                .build())
        .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(limit).build())
        .disableRedirectHandling()
        .disableAutomaticRetries()
        .disableCookieManagement()
        .disableAuthCaching()
        .build();
  }

  /** Answers the checked host with its checked addresses; any other name does not resolve. */
  record PinnedDns(String host, List<InetAddress> addresses) implements DnsResolver {

    @Override
    public InetAddress[] resolve(String name) throws UnknownHostException {
      if (bare(name).equals(bare(host))) {
        return addresses.toArray(InetAddress[]::new);
      }
      throw new UnknownHostException("not the checked IPN host");
    }

    @Override
    public String resolveCanonicalHostname(String name) {
      return name;
    }

    private static String bare(String name) {
      String n = name.toLowerCase(Locale.ROOT);
      return n.startsWith("[") && n.endsWith("]") ? n.substring(1, n.length() - 1) : n;
    }
  }

  private static String errorOf(Throwable t) {
    Throwable cause = t instanceof UncheckedIOException u ? u.getCause() : t;
    if (cause instanceof ConnectTimeoutException) {
      return "CONNECT_TIMEOUT";
    }
    if (cause instanceof SocketTimeoutException) {
      return "TIMEOUT";
    }
    if (cause instanceof ConnectException) {
      return "CONNECT_FAILED";
    }
    if (cause instanceof SSLException) {
      return "TLS_FAILED";
    }
    return "IO_ERROR";
  }

  private static long millisSince(long start) {
    return Duration.ofNanos(System.nanoTime() - start).toMillis();
  }
}

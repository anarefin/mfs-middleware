package mfs.mw.crypto.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code mw.crypto.payshield}: the Thales payShield 10K provider (ticket 64, spec 18.8, D23); in dev
 * and test the simulator in mw-simulators. Off by default (the production HSM vendor is open point
 * 22); the dev stack turns it on.
 *
 * @param enabled register the provider
 * @param host the HSM host ({@code mw-simulators} in the stack)
 * @param port the host command port (1500)
 * @param headerLength characters of the message header the HSM echoes (4): the request/response
 *     correlation
 * @param poolSize at most this many connections, one command in flight on each
 * @param connectTimeout TCP connect limit
 * @param callTimeout how long one command may take before the request fails closed
 * @param acquireTimeout how long a request waits for a free connection
 * @param bootstrapPurposes the payShield purposes the dev bootstrap adds to {@code
 *     mw.crypto.bootstrap-purposes} while the provider is on
 */
@ConfigurationProperties("mw.crypto.payshield")
public record PayShieldProperties(
    boolean enabled,
    String host,
    int port,
    int headerLength,
    int poolSize,
    Duration connectTimeout,
    Duration callTimeout,
    Duration acquireTimeout,
    List<String> bootstrapPurposes) {

  public PayShieldProperties {
    host = host == null ? "mw-simulators" : host;
    port = port == 0 ? 1500 : port;
    headerLength = headerLength == 0 ? 4 : headerLength;
    poolSize = poolSize == 0 ? 4 : poolSize;
    connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
    callTimeout = callTimeout == null ? Duration.ofSeconds(3) : callTimeout;
    acquireTimeout = acquireTimeout == null ? Duration.ofSeconds(2) : acquireTimeout;
    bootstrapPurposes = List.copyOf(bootstrapPurposes == null ? List.of() : bootstrapPurposes);
  }
}

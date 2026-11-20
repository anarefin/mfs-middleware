package mfs.mw.ussd.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code mw.ussd.smpp.*}: the SMPP USSD transport's binds (spec 9.2, 9.2.1, WP19). One bind group
 * per MNO whose {@code mw.ussd.mno.<mno>.transport} is SMPP; an MNO switches transport by
 * configuration alone. Passwords come from Vault ({@code secret/mw-ussd}, key {@code
 * mw.ussd.smpp.mno.<mno>.password}), never from a file here.
 *
 * @param enabled false binds nothing (tests); an SMPP MNO then gets no traffic
 * @param mno bind settings per MNO
 */
@ConfigurationProperties("mw.ussd.smpp")
public record UssdSmppProperties(boolean enabled, Map<String, Endpoint> mno) {

  /** How one MNO's binds are opened (CH-USSD-01). */
  public enum BindMode {
    /** One {@code bind_transceiver} session per bind. */
    TRANSCEIVER,
    /** A {@code bind_transmitter} and a {@code bind_receiver} per bind. */
    TRANSMITTER_RECEIVER
  }

  public UssdSmppProperties {
    mno = Map.copyOf(mno == null ? Map.of() : mno);
  }

  /**
   * One MNO's USSD gateway.
   *
   * @param binds sessions held for throughput (MNO contract value, TBC); a
   *     {@link BindMode#TRANSMITTER_RECEIVER} bind is one transmitter plus one receiver
   * @param bindMode {@link BindMode#TRANSCEIVER} or {@link BindMode#TRANSMITTER_RECEIVER}
   * @param serviceType {@code service_type} of the {@code submit_sm} (USSD)
   * @param enquireLink {@code enquire_link} interval (30 s)
   * @param enquireLinkMisses unanswered enquire_links in a row before the bind is dropped and
   *     re-established (3)
   * @param transactionTimeout how long a request waits for its response
   * @param reconnectMin first reconnect delay (1 s), doubled per failure up to {@code
   *     reconnectMax} (60 s)
   * @param window outstanding {@code submit_sm} per bind (MNO contract value, TBC)
   * @param tps {@code submit_sm} per second per bind (MNO contract value, TBC)
   * @param retryDelays delays before each retry of a {@code submit_sm} refused with {@code
   *     generic_nack}, throttling (0x58) or queue full (0x14)
   */
  public record Endpoint(
      String host,
      int port,
      String systemId,
      String password,
      String systemType,
      String serviceType,
      int binds,
      Duration enquireLink,
      int enquireLinkMisses,
      Duration transactionTimeout,
      Duration reconnectMin,
      Duration reconnectMax,
      int window,
      int tps,
      List<Duration> retryDelays,
      BindMode bindMode) {

    public Endpoint {
      password = password == null ? "" : password;
      systemType = systemType == null ? "" : systemType;
      serviceType = serviceType == null ? "USSD" : serviceType;
      binds = Math.max(1, binds);
      enquireLink = enquireLink == null ? Duration.ofSeconds(30) : enquireLink;
      enquireLinkMisses = enquireLinkMisses <= 0 ? 3 : enquireLinkMisses;
      transactionTimeout = transactionTimeout == null ? Duration.ofSeconds(5) : transactionTimeout;
      reconnectMin = reconnectMin == null ? Duration.ofSeconds(1) : reconnectMin;
      reconnectMax = reconnectMax == null ? Duration.ofSeconds(60) : reconnectMax;
      window = window <= 0 ? 10 : window;
      tps = tps <= 0 ? 50 : tps;
      retryDelays =
          List.copyOf(retryDelays == null
              ? List.of(Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2))
              : retryDelays);
      bindMode = bindMode == null ? BindMode.TRANSCEIVER : bindMode;
    }

    @Override
    public String toString() {
      return "Endpoint[host=" + host + ", port=" + port + ", systemId=" + systemId + ", password=****, binds="
          + binds + ", window=" + window + ", tps=" + tps + "]";
    }
  }
}

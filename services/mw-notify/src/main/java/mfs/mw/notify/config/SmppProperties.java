package mfs.mw.notify.config;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import mfs.mw.notify.dto.Priority;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SMPP 3.4 connection to the SMSC (spec 10.2, 9.2.1). {@code password} comes from OpenBao
 * ({@code secret/mw-notify}, key {@code mw.notify.smpp.password}), never from a file here.
 *
 * @param enabled false switches the SMSC client off (tests); the dispatcher then fails every send
 *     as transient
 * @param binds transceiver binds per priority (10.1: separate binds so PROMO never delays OTP)
 * @param enquireLink {@code enquire_link} interval (9.2.1: 30 s)
 * @param transactionTimeout how long a {@code submit_sm} waits for its response
 * @param reconnectMin first reconnect delay after a bind fails (9.2.1: exponential 1-60 s)
 * @param reconnectMax longest reconnect delay
 */
@ConfigurationProperties("mw.notify.smpp")
public record SmppProperties(
    boolean enabled,
    String host,
    int port,
    String systemId,
    String password,
    String systemType,
    Map<Priority, Integer> binds,
    Duration enquireLink,
    Duration transactionTimeout,
    Duration reconnectMin,
    Duration reconnectMax) {

  public SmppProperties {
    Map<Priority, Integer> all = new EnumMap<>(Priority.class);
    for (Priority p : Priority.values()) {
      all.put(p, binds == null || binds.get(p) == null ? 1 : Math.max(1, binds.get(p)));
    }
    binds = Map.copyOf(all);
    password = password == null ? "" : password;
    systemType = systemType == null ? "" : systemType;
    enquireLink = enquireLink == null ? Duration.ofSeconds(30) : enquireLink;
    transactionTimeout = transactionTimeout == null ? Duration.ofSeconds(5) : transactionTimeout;
    reconnectMin = reconnectMin == null ? Duration.ofSeconds(1) : reconnectMin;
    reconnectMax = reconnectMax == null ? Duration.ofSeconds(60) : reconnectMax;
  }

  @Override
  public String toString() {
    return "SmppProperties[enabled=" + enabled + ", host=" + host + ", port=" + port + ", systemId=" + systemId
        + ", password=****, binds=" + binds + "]";
  }
}

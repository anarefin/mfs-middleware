package mfs.mw.crypto.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import mfs.mw.crypto.client.CryptoProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Whether each provider's HSM answers (spec 18.8, 21.4): gauge {@code crypto.hsm.up{provider}} (1 up,
 * 0 down; 0 until the first probe) and, on every change, a log event: ERROR {@code crypto.hsm.down}
 * (the alert: mw-crypto is on the critical path, D23) or INFO {@code crypto.hsm.up}. Called by
 * {@code scheduler/HsmProbeJob}. Readiness does not depend on it: requests fail closed instead.
 */
@Service
public class HsmHealthService {

  private static final Logger log = LoggerFactory.getLogger(HsmHealthService.class);

  private final List<CryptoProvider> providers;
  private final Map<String, AtomicInteger> up = new ConcurrentHashMap<>();
  private final Map<String, Boolean> lastLogged = new ConcurrentHashMap<>();

  public HsmHealthService(List<CryptoProvider> providers, MeterRegistry meters) {
    this.providers = List.copyOf(providers);
    for (CryptoProvider p : providers) {
      AtomicInteger value = new AtomicInteger();
      up.put(p.name(), value);
      Gauge.builder("crypto.hsm.up", value, AtomicInteger::get)
          .description("1 when the provider's HSM answers the probe, else 0")
          .tag("provider", p.name())
          .register(meters);
    }
  }

  /** Probes every provider once and updates the gauges. */
  public void probe() {
    for (CryptoProvider p : providers) {
      boolean ok = p.probe();
      up.get(p.name()).set(ok ? 1 : 0);
      Boolean before = lastLogged.put(p.name(), ok);
      if (before == null || before != ok) {
        if (ok) {
          log.atInfo().addKeyValue("event", "crypto.hsm.up").addKeyValue("provider", p.name()).log("HSM reachable");
        } else {
          log.atError()
              .addKeyValue("event", "crypto.hsm.down")
              .addKeyValue("provider", p.name())
              .log("HSM unreachable: every request that needs it fails closed");
        }
      }
    }
  }

  /** The gauge value of {@code provider}: 1 up, 0 down or not probed yet. */
  public int up(String provider) {
    AtomicInteger v = up.get(provider);
    return v == null ? 0 : v.get();
  }
}

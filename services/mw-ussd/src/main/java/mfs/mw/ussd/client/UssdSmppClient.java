package mfs.mw.ussd.client;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import mfs.mw.ussd.config.UssdSmppProperties;
import mfs.mw.ussd.dto.SmppUssdMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The SMPP binds of every MNO whose USSD runs over SMPP (spec 9.2, 9.2.1): one bind group per MNO
 * ({@code <mno>-1 .. <mno>-n}), all held by this instance for throughput. Inbound {@code
 * deliver_sm} go to the registered {@link UssdSmppListener}; {@link #submit} sends a screen on the
 * named bind (a session's responses use the bind that received it). Gauge {@code mw.smpp.bind.up}
 * (Prometheus {@code mw_smpp_bind_up}, tags {@code mno}, {@code bind}) is 1 while a bind is up.
 */
public class UssdSmppClient implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(UssdSmppClient.class);

  private final Map<String, UssdSmppBind> binds = new LinkedHashMap<>();
  private volatile UssdSmppListener listener;
  private volatile boolean running;

  /**
   * @param smppMnos the MNOs configured with transport SMPP; others are not bound even when their
   *     endpoint is configured (config-ready)
   */
  public UssdSmppClient(UssdSmppProperties props, Set<String> smppMnos, MeterRegistry meters, Clock clock) {
    if (!props.enabled()) {
      return;
    }
    for (String mno : new TreeSet<>(smppMnos)) {
      UssdSmppProperties.Endpoint ep = props.mno().get(mno);
      if (ep == null || ep.host() == null || ep.host().isBlank()) {
        log.atError().addKeyValue("event", "smpp.config.missing").addKeyValue("mno", mno)
            .log("USSD transport SMPP without mw.ussd.smpp.mno settings; not bound");
        continue;
      }
      for (int i = 1; i <= ep.binds(); i++) {
        UssdSmppBind bind = new UssdSmppBind(mno + "-" + i, mno, ep, this::dispatch, clock);
        binds.put(bind.name(), bind);
        Gauge.builder("mw.smpp.bind.up", bind, b -> b.isUp() ? 1 : 0)
            .description("1 while the SMPP bind is up")
            .tags("mno", mno, "bind", bind.name())
            .strongReference(true)
            .register(meters);
      }
    }
  }

  /** Where inbound deliver_sm go (the SMPP transport registers itself). */
  public void listen(UssdSmppListener l) {
    this.listener = l;
  }

  /** The bind names of {@code mno}, in order. */
  public List<String> binds(String mno) {
    return binds.values().stream().filter(b -> b.mno().equals(mno)).map(UssdSmppBind::name).toList();
  }

  public boolean isUp(String bind) {
    UssdSmppBind b = binds.get(bind);
    return b != null && b.isUp();
  }

  /** Sends {@code m} on {@code m.bind()}, with the bind's window, throttle and delayed retries. */
  public void submit(SmppUssdMessage m) throws UssdSmppException {
    UssdSmppBind bind = binds.get(m.bind());
    if (bind == null) {
      throw new UssdSmppException("no such bind", -1);
    }
    bind.submit(m);
  }

  private void dispatch(SmppUssdMessage m) {
    UssdSmppListener l = listener;
    if (l == null) {
      throw new IllegalStateException("no USSD listener");
    }
    l.onDeliver(m);
  }

  @Override
  public synchronized void start() {
    binds.values().forEach(UssdSmppBind::start);
    running = true;
  }

  @Override
  public synchronized void stop() {
    running = false;
    binds.values().forEach(UssdSmppBind::stop);
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}

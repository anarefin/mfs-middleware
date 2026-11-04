package mfs.mw.ussd.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import mfs.mw.ussd.client.UssdSmppClient;
import mfs.mw.ussd.client.UssdSmppException;
import mfs.mw.ussd.client.UssdSmppListener;
import mfs.mw.ussd.config.UssdProperties;
import mfs.mw.ussd.dto.SmppUssdMessage;
import mfs.mw.ussd.dto.UssdEvent;
import mfs.mw.ussd.dto.UssdRequest;
import mfs.mw.ussd.dto.UssdResponse;
import mfs.mw.ussd.util.Msisdn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@code SmppUssdTransport} (spec 9.2, WP19): {@code deliver_sm} in, {@code submit_sm} out, on the
 * binds of {@link UssdSmppClient}. {@code ussd_service_op} 1 (PSSR indication) begins a session,
 * 18 (USSR confirm) continues it, the end-of-session bit of {@code its_session_info} aborts it
 * (subscriber released); screens go out as 2 (USSR request) or, when the menu ends, 17 (PSSR
 * response) with end-of-session set. The session id is the {@code its_session_info} session
 * number plus the source address (hashed: no MSISDN in Valkey keys), and a session's responses go
 * out on the bind that received it. Each step runs on its own virtual thread, never on jSMPP's
 * PDU threads (an ACTION waits up to the sync budget).
 */
@Service
public class SmppUssdTransport implements UssdTransport, UssdSmppListener {

  private static final Logger log = LoggerFactory.getLogger(SmppUssdTransport.class);

  /** Where a session's screens go: its bind, the subscriber address and the last sequence. */
  private record Route(String mno, String bind, String address, String serviceCode, int sessionNumber, int sequence,
      Instant seen) {}

  private final UssdEngine engine;
  private final UssdSmppClient client;
  private final UssdProperties props;
  private final Clock clock;
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private final Map<String, Route> routes = new ConcurrentHashMap<>();

  public SmppUssdTransport(UssdEngine engine, UssdSmppClient client, UssdProperties props, Clock clock) {
    this.engine = engine;
    this.client = client;
    this.props = props;
    this.clock = clock;
  }

  /** Registers with the binds before they start (SmartLifecycle runs after initialisation). */
  @PostConstruct
  void register() {
    client.listen(this);
  }

  @Override
  public UssdProperties.Transport type() {
    return UssdProperties.Transport.SMPP;
  }

  @Override
  public void onDeliver(SmppUssdMessage message) {
    workers.execute(() -> step(message));
  }

  /** One deliver_sm, on a worker thread. */
  void step(SmppUssdMessage m) {
    UssdProperties.Mno config = props.mno().get(m.mno());
    if (config == null || config.transport() != UssdProperties.Transport.SMPP) {
      log.atWarn().addKeyValue("event", "ussd.smpp.ignored").addKeyValue("mno", m.mno())
          .log("deliver_sm for an MNO not on SMPP");
      return;
    }
    String msisdn = Msisdn.canonical(m.address());
    if (msisdn == null) {
      log.atWarn().addKeyValue("event", "ussd.smpp.ignored").addKeyValue("mno", m.mno())
          .log("deliver_sm from an address that is not a mobile number");
      return;
    }
    purge(config);
    String sessionId = sessionId(m.sessionNumber(), msisdn);
    if (m.endOfSession()) {
      routes.remove(sessionId);
      onAbort(new UssdRequest(m.mno(), sessionId, msisdn, UssdEvent.ABORT, null));
      return;
    }
    UssdEvent event =
        switch (m.serviceOp()) {
          case SmppUssdMessage.PSSR_INDICATION -> UssdEvent.BEGIN;
          case SmppUssdMessage.USSR_CONFIRM -> UssdEvent.CONTINUE;
          default -> null;
        };
    if (event == null) {
      log.atWarn().addKeyValue("event", "ussd.smpp.ignored").addKeyValue("mno", m.mno())
          .addKeyValue("serviceOp", m.serviceOp()).log("deliver_sm with an unexpected ussd_service_op");
      return;
    }
    routes.put(sessionId, new Route(m.mno(), m.bind(), m.address(), m.serviceCode(), m.sessionNumber(), m.sequence(),
        clock.instant()));
    UssdRequest req = new UssdRequest(m.mno(), sessionId, msisdn, event, m.text());
    send(event == UssdEvent.BEGIN ? onBegin(req) : onContinue(req));
  }

  @Override
  public UssdResponse onBegin(UssdRequest request) {
    return engine.handle(request);
  }

  @Override
  public UssdResponse onContinue(UssdRequest request) {
    return engine.handle(request);
  }

  @Override
  public void onEnd(UssdRequest request) {
    engine.handle(request);
  }

  @Override
  public void onAbort(UssdRequest request) {
    engine.handle(request);
  }

  /** Sends a screen as submit_sm on the bind the session came in on. */
  @Override
  public void send(UssdResponse response) {
    Route route = response.end() ? routes.remove(response.sessionId()) : routes.get(response.sessionId());
    if (route == null) {
      log.atWarn().addKeyValue("event", "ussd.smpp.no_session").log("no SMPP session for a USSD screen");
      return;
    }
    SmppUssdMessage out = new SmppUssdMessage(route.mno(), route.bind(), route.address(), route.serviceCode(),
        response.end() ? SmppUssdMessage.PSSR_RESPONSE : SmppUssdMessage.USSR_REQUEST, route.sessionNumber(),
        route.sequence() + 1, response.end(), response.text());
    try {
      client.submit(out);
    } catch (UssdSmppException e) {
      log.atError().addKeyValue("event", "ussd.smpp.send_failed").addKeyValue("mno", route.mno())
          .addKeyValue("bind", route.bind()).addKeyValue("commandStatus", e.commandStatus())
          .log("USSD screen not delivered");
    }
  }

  /** Forgets routes of sessions the MNO has long timed out. */
  private void purge(UssdProperties.Mno config) {
    Instant cutoff = clock.instant().minus(config.sessionTimeout().plus(props.sessionGrace()));
    routes.values().removeIf(r -> r.seen().isBefore(cutoff));
  }

  static String sessionId(int sessionNumber, String msisdn) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(msisdn.getBytes(StandardCharsets.UTF_8));
      return "smpp-" + sessionNumber + "-" + HexFormat.of().formatHex(digest, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @PreDestroy
  void shutdown() {
    workers.shutdown();
    try {
      workers.awaitTermination(Duration.ofSeconds(5).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}

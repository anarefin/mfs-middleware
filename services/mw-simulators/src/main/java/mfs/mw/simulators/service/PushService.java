package mfs.mw.simulators.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import mfs.mw.simulators.config.PushProperties;
import mfs.mw.simulators.config.SimulatorProperties;
import mfs.mw.simulators.dto.PushMessage;
import mfs.mw.simulators.dto.PushMessageRequest;
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.exception.MockAbortException;
import mfs.mw.simulators.service.mockcontrol.Decision;
import mfs.mw.simulators.service.mockcontrol.MockCalls;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import mfs.mw.simulators.service.mockcontrol.PartyState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The push server mock (ticket 110, FRS EXT-04; mock party {@code push}, operation {@code send}, keys
 * {@code token} and {@code topic}): an FCM / APNs style app-server push API that keeps every push it
 * accepts. {@code Authorization: key=<sim.push.api-key>} is required (401 otherwise, also when no key is
 * set); a push without token and topic or without a notification is 400.
 *
 * <p>Outcomes: SUCCESS stores the push and answers 200 {@code {messageId: push-<n>, status: DELIVERED}};
 * PARTIAL_SUCCESS stores it as {@code ACCEPTED} (taken, not yet delivered); BUSINESS_ERROR (magic value: a
 * token containing {@code invalid}) answers 404 {@code {error: <code, default UNREGISTERED>}} and stores
 * nothing; HTTP_5XX answers its {@code httpStatus} (default 500) and stores nothing (a push server that
 * failed delivered nothing, so a retried push never shows twice); TIMEOUT stores, then holds the answer;
 * MALFORMED_RESPONSE stores and cuts the body short; CONNECTION_RESET stores and closes the connection;
 * DOWN closes it, MAINTENANCE 503, nothing stored. In memory; the party reset forgets the pushes.
 */
@Service
public class PushService implements PartyState {

  public static final String PARTY = "push";

  private static final Logger log = LoggerFactory.getLogger(PushService.class);

  private final MockControl control;
  private final PushProperties props;
  private final Clock clock;
  private final int recordLimit;
  private final List<PushMessage> messages = new ArrayList<>();
  private long seq;

  public PushService(MockControl control, PushProperties props, Clock clock, SimulatorProperties sim) {
    this.control = control;
    this.props = props;
    this.clock = clock;
    this.recordLimit = sim.recordLimit();
  }

  public SimReply send(String authorization, PushMessageRequest req) {
    if (!authorised(authorization)) {
      return new SimReply(401, Map.of("error", "UNAUTHENTICATED"));
    }
    if (req == null || (blank(req.token()) && blank(req.topic())) || req.notification() == null) {
      return new SimReply(400, Map.of("error", "INVALID_ARGUMENT"));
    }
    Map<String, String> keys = new HashMap<>();
    if (!blank(req.token())) {
      keys.put("token", req.token());
    }
    if (!blank(req.topic())) {
      keys.put("topic", req.topic());
    }
    Decision d = control.decide(PARTY, "send", keys, magic(req));
    Optional<SimReply> closed = MockCalls.admit(d, PARTY, () -> new SimReply(503, Map.of("error", "UNAVAILABLE")));
    if (closed.isPresent()) {
      return closed.get();
    }
    if (d.mode() == SimMode.BUSINESS_FAILURE || d.mode() == SimMode.UNKNOWN_CODE) {
      return new SimReply(404, Map.of("error", d.codeOr("UNREGISTERED")));
    }
    if (d.mode() == SimMode.SERVER_ERROR_AFTER_SEND) {
      return new SimReply(d.statusOr(500), Map.of("error", "INTERNAL"));
    }
    PushMessage stored = store(req, d.mode() == SimMode.PARTIAL_SUCCESS ? "ACCEPTED" : "DELIVERED");
    log.atInfo()
        .addKeyValue("event", "sim.push.stored")
        .addKeyValue("partnerId", PARTY)
        .log("push {} stored ({})", stored.messageId(), d.source());
    Map<String, String> ack = Map.of("messageId", stored.messageId(), "status", stored.status());
    return switch (d.mode()) {
      case TIMEOUT -> {
        MockCalls.pause(d.holdMs());
        yield new SimReply(200, ack);
      }
      case MALFORMED -> new SimReply(200, MockCalls.CUT_SHORT);
      case CONNECTION_RESET -> throw new MockAbortException(PARTY);
      default -> new SimReply(200, ack);
    };
  }

  /** The accepted pushes, oldest first, for this token and / or topic when given. */
  public synchronized List<PushMessage> messages(String token, String topic) {
    return messages.stream()
        .filter(m -> token == null || token.equals(m.token()))
        .filter(m -> topic == null || topic.equals(m.topic()))
        .toList();
  }

  @Override
  public synchronized void reset(String party) {
    if (PARTY.equals(party)) {
      messages.clear();
    }
  }

  private synchronized PushMessage store(PushMessageRequest req, String status) {
    seq++;
    PushMessage m = new PushMessage(seq, "push-" + seq, req.messageId(), req.token(), req.topic(), req.platform(),
        req.notification().title(), req.notification().body(),
        req.data() == null ? Map.of() : Map.copyOf(req.data()), status, clock.instant());
    messages.add(m);
    while (messages.size() > recordLimit) {
      messages.removeFirst();
    }
    return m;
  }

  private boolean authorised(String authorization) {
    String key = props.apiKey();
    if (blank(key) || authorization == null || !authorization.startsWith("key=")) {
      return false;
    }
    return MessageDigest.isEqual(
        key.getBytes(StandardCharsets.UTF_8), authorization.substring(4).getBytes(StandardCharsets.UTF_8));
  }

  /** A token containing {@code invalid} is not registered at the push server. */
  private static SimMode magic(PushMessageRequest req) {
    return req.token() != null && req.token().toLowerCase(Locale.ROOT).contains("invalid")
        ? SimMode.BUSINESS_FAILURE
        : SimMode.SUCCESS;
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}

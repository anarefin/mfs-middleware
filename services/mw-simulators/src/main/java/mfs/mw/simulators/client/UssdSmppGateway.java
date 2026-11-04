package mfs.mw.simulators.client;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import mfs.mw.simulators.config.UssdSmppGatewayProperties;
import mfs.mw.simulators.dto.UssdDialRequest;
import mfs.mw.simulators.dto.UssdDialResult;
import mfs.mw.simulators.dto.UssdScreen;
import mfs.mw.simulators.dto.UssdSmppBinds;
import mfs.mw.simulators.dto.UssdSmppScenario;
import org.jsmpp.DefaultPDUReader;
import org.jsmpp.DefaultPDUSender;
import org.jsmpp.SMPPConstant;
import org.jsmpp.SynchronizedPDUSender;
import org.jsmpp.bean.BroadcastSm;
import org.jsmpp.bean.CancelBroadcastSm;
import org.jsmpp.bean.CancelSm;
import org.jsmpp.bean.DataCodings;
import org.jsmpp.bean.DataSm;
import org.jsmpp.bean.ESMClass;
import org.jsmpp.bean.NumberingPlanIndicator;
import org.jsmpp.bean.OptionalParameter;
import org.jsmpp.bean.QueryBroadcastSm;
import org.jsmpp.bean.QuerySm;
import org.jsmpp.bean.RegisteredDelivery;
import org.jsmpp.bean.ReplaceSm;
import org.jsmpp.bean.SubmitMulti;
import org.jsmpp.bean.SubmitSm;
import org.jsmpp.bean.TypeOfNumber;
import org.jsmpp.extra.ProcessRequestException;
import org.jsmpp.session.BindRequest;
import org.jsmpp.session.BroadcastSmResult;
import org.jsmpp.session.DataSmResult;
import org.jsmpp.session.QueryBroadcastSmResult;
import org.jsmpp.session.QuerySmResult;
import org.jsmpp.session.SMPPServerSession;
import org.jsmpp.session.ServerMessageReceiverListener;
import org.jsmpp.session.Session;
import org.jsmpp.session.SubmitMultiResult;
import org.jsmpp.session.SubmitSmResult;
import org.jsmpp.session.connection.Connection;
import org.jsmpp.session.connection.ServerConnection;
import org.jsmpp.session.connection.socket.ServerSocketConnectionFactory;
import org.jsmpp.util.MessageId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The SMPP side of the USSD gateway simulator (spec 22.2, ticket 27): an SMPP 3.4 server on
 * {@code sim.ussd-gateway.smpp.port} that mw-ussd binds to as transceiver (any bind accepted).
 * {@link #play} plays a subscriber: a {@code deliver_sm} with {@code ussd_service_op} 1 (PSSR
 * indication) and the service code, then one with 18 (USSR confirm) per input, each with {@code
 * its_session_info} (session number; sequence in bits 7..1), waiting for mw-ussd's {@code
 * submit_sm} of that session (ussd_service_op 17 or the end-of-session bit = END, else CON).
 * Misbehaviour on demand ({@link #scenario}): refuse the next submits with 0x58 / 0x14 / {@code
 * generic_nack}, stop answering {@code enquire_link}; {@link #drop} closes every bind.
 */
@Component
public class UssdSmppGateway implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(UssdSmppGateway.class);

  /** command_status the receiver throws to make the PDU sender answer with a generic_nack. */
  private static final int NACK_MARKER = 0x0400;
  private static final int PSSR_INDICATION = 1;
  private static final int PSSR_RESPONSE = 17;
  private static final int USSR_CONFIRM = 18;
  private static final String GSM_BASIC =
      "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ\u001bÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
          + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";

  /** One screen mw-ussd sent. */
  private record Screen(String text, boolean end) {}

  private final UssdSmppGatewayProperties props;
  private final List<SMPPServerSession> sessions = new CopyOnWriteArrayList<>();
  private final Map<Integer, BlockingQueue<Screen>> waiting = new ConcurrentHashMap<>();
  private final AtomicInteger binds = new AtomicInteger();
  private final AtomicInteger enquireLinks = new AtomicInteger();
  private final AtomicInteger refused = new AtomicInteger();
  private final AtomicInteger throttle = new AtomicInteger();
  private final AtomicInteger queueFull = new AtomicInteger();
  private final AtomicInteger genericNack = new AtomicInteger();
  private final AtomicInteger sessionNumbers = new AtomicInteger();
  private final AtomicInteger cursor = new AtomicInteger();
  private volatile boolean silent;
  private volatile Duration screenTimeout;
  private volatile boolean running;
  private ServerConnection server;

  public UssdSmppGateway(UssdSmppGatewayProperties props) {
    this.props = props;
    this.screenTimeout = props.screenTimeout();
  }

  /** Whether {@code mno} is played over SMPP. */
  public boolean plays(String mno) {
    return props.mnos().contains(mno);
  }

  @Override
  public synchronized void start() {
    if (!props.enabled()) {
      return;
    }
    try {
      server = new ServerSocketConnectionFactory().listen(props.port());
    } catch (IOException e) {
      throw new IllegalStateException("USSD SMPP gateway cannot listen on " + props.port(), e);
    }
    running = true;
    Thread.ofPlatform().name("ussd-smpp-accept").daemon().start(this::acceptLoop);
    log.atInfo().addKeyValue("event", "sim.ussd.smpp.listening").addKeyValue("port", props.port())
        .log("USSD SMPP gateway simulator listening");
  }

  @Override
  public synchronized void stop() {
    running = false;
    drop();
    if (server != null) {
      try {
        server.close();
      } catch (IOException e) {
        log.atWarn().addKeyValue("event", "sim.ussd.smpp.close_failed").log("listener did not close cleanly");
      }
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** Replaces the misbehaviour scenario. */
  public void scenario(UssdSmppScenario s) {
    throttle.set(s.throttleNext() == null ? 0 : s.throttleNext());
    queueFull.set(s.queueFullNext() == null ? 0 : s.queueFullNext());
    genericNack.set(s.genericNackNext() == null ? 0 : s.genericNackNext());
    silent = Boolean.TRUE.equals(s.silentEnquireLink());
    screenTimeout = s.screenTimeoutMs() == null ? props.screenTimeout() : Duration.ofMillis(s.screenTimeoutMs());
  }

  /** Closes every bind, as a gateway restart would; mw-ussd reconnects. */
  public void drop() {
    List<SMPPServerSession> all = new ArrayList<>(sessions);
    sessions.clear();
    all.forEach(SMPPServerSession::close);
    log.atInfo().addKeyValue("event", "sim.ussd.smpp.dropped").addKeyValue("binds", all.size())
        .log("USSD SMPP binds dropped");
  }

  public UssdSmppBinds binds() {
    int bound = (int) sessions.stream().filter(s -> s.getSessionState().isBound()).count();
    return new UssdSmppBinds(bound, binds.get(), enquireLinks.get(), refused.get());
  }

  /**
   * Plays one session on the next bound session (round robin). A step without a screen within
   * the screen timeout (or without any bind) is recorded with status -1 and ends the play.
   */
  public UssdDialResult play(UssdDialRequest req) {
    int number = (sessionNumbers.incrementAndGet() & 0x7F) + 1;
    String sessionId = "smpp-" + number;
    String code = req.serviceCode() == null || req.serviceCode().isBlank() ? "*268#" : req.serviceCode();
    BlockingQueue<Screen> queue = new LinkedBlockingQueue<>();
    waiting.put(number, queue);
    List<UssdScreen> screens = new ArrayList<>();
    try {
      SMPPServerSession session = next();
      Screen s = session == null ? null : step(session, queue, req.msisdn(), code, PSSR_INDICATION, number, 0, code);
      screens.add(screen(s));
      int seq = 0;
      for (String input : req.inputs()) {
        if (s == null || s.end()) {
          break;
        }
        seq = (seq + 2) & 0x7F;
        s = step(session, queue, req.msisdn(), code, USSR_CONFIRM, number, seq, input);
        screens.add(screen(s));
      }
      return new UssdDialResult(sessionId, List.copyOf(screens), s != null && s.end());
    } finally {
      waiting.remove(number);
    }
  }

  private static UssdScreen screen(Screen s) {
    return s == null ? new UssdScreen(-1, null, null) : new UssdScreen(200, s.text(), s.end() ? "END" : "CON");
  }

  private SMPPServerSession next() {
    List<SMPPServerSession> up = sessions.stream().filter(s -> s.getSessionState().isBound()).toList();
    return up.isEmpty() ? null : up.get(Math.floorMod(cursor.getAndIncrement(), up.size()));
  }

  private Screen step(SMPPServerSession session, BlockingQueue<Screen> queue, String address, String code, int op,
      int number, int seq, String text) {
    boolean ascii = text.chars().allMatch(c -> c >= 0x20 && c < 0x7F && c != '@' && c != '$' && c != '_');
    byte[] body = ascii ? text.getBytes(StandardCharsets.US_ASCII) : text.getBytes(StandardCharsets.UTF_16BE);
    try {
      session.deliverShortMessage("USSD", TypeOfNumber.UNKNOWN, NumberingPlanIndicator.ISDN, address,
          TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, code, new ESMClass(), (byte) 0, (byte) 0,
          new RegisteredDelivery(0), ascii ? DataCodings.ZERO : DataCodings.newInstance((byte) 8), body,
          new OptionalParameter.OctetString(OptionalParameter.Tag.USSD_SERVICE_OP.code(), new byte[] {(byte) op}),
          new OptionalParameter.OctetString(OptionalParameter.Tag.ITS_SESSION_INFO.code(),
              new byte[] {(byte) number, (byte) (seq << 1)}));
      return queue.poll(screenTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } catch (Exception e) {
      log.atWarn().addKeyValue("event", "sim.ussd.smpp.deliver_failed").addKeyValue("errorClass", e.getClass().getName())
          .log("deliver_sm not accepted");
      return null;
    }
  }

  private void acceptLoop() {
    while (running) {
      try {
        Connection conn = server.accept();
        conn.setSoTimeout(5000);
        SMPPServerSession session = new SMPPServerSession(conn, null, new Receiver(), null, 3, 100,
            new SynchronizedPDUSender(new Sender()), new DefaultPDUReader());
        Thread.ofPlatform().daemon().name("ussd-smpp-bind").start(() -> bind(session));
      } catch (IOException e) {
        if (running) {
          log.atWarn().addKeyValue("event", "sim.ussd.smpp.accept_failed").log("SMPP accept failed");
        }
      }
    }
  }

  private void bind(SMPPServerSession session) {
    try {
      BindRequest bind = session.waitForBind(5000);
      bind.accept("upay-ussdgw");
      binds.incrementAndGet();
      sessions.add(session);
      log.atInfo().addKeyValue("event", "sim.ussd.smpp.bound").addKeyValue("bindType", bind.getBindType())
          .log("USSD SMPP client bound");
    } catch (Exception e) {
      log.atWarn().addKeyValue("event", "sim.ussd.smpp.bind_failed").addKeyValue("errorClass", e.getClass().getName())
          .log("USSD SMPP bind failed");
      session.close();
    }
  }

  static byte[] tlv(OptionalParameter[] params, OptionalParameter.Tag tag) {
    if (params == null) {
      return null;
    }
    for (OptionalParameter p : params) {
      if (p.tag == tag.code()) {
        byte[] all = p.serialize();
        return Arrays.copyOfRange(all, 4, all.length);
      }
    }
    return null;
  }

  static String decode(byte dataCoding, byte[] bytes) {
    if (dataCoding == 0x08) {
      return new String(bytes, StandardCharsets.UTF_16BE);
    }
    StringBuilder out = new StringBuilder(bytes.length);
    for (byte b : bytes) {
      out.append(GSM_BASIC.charAt(b & 0x7F));
    }
    return out.toString();
  }

  /** Answers like the default sender, except generic_nack on demand and silence on enquire_link. */
  private final class Sender extends DefaultPDUSender {

    @Override
    public byte[] sendHeader(OutputStream os, int commandId, int commandStatus, int sequenceNumber)
        throws IOException {
      if (commandStatus == NACK_MARKER) {
        return sendGenericNack(os, SMPPConstant.STAT_ESME_RTHROTTLED, sequenceNumber);
      }
      return super.sendHeader(os, commandId, commandStatus, sequenceNumber);
    }

    @Override
    public byte[] sendEnquireLinkResp(OutputStream os, int sequenceNumber) throws IOException {
      enquireLinks.incrementAndGet();
      return silent ? new byte[0] : super.sendEnquireLinkResp(os, sequenceNumber);
    }
  }

  private final class Receiver implements ServerMessageReceiverListener {

    @Override
    public SubmitSmResult onAcceptSubmitSm(SubmitSm sm, SMPPServerSession session) throws ProcessRequestException {
      if (take(genericNack)) {
        throw new ProcessRequestException("generic_nack", NACK_MARKER);
      }
      if (take(throttle)) {
        throw new ProcessRequestException("throttled", SMPPConstant.STAT_ESME_RTHROTTLED);
      }
      if (take(queueFull)) {
        throw new ProcessRequestException("queue full", SMPPConstant.STAT_ESME_RMSGQFUL);
      }
      byte[] op = tlv(sm.getOptionalParameters(), OptionalParameter.Tag.USSD_SERVICE_OP);
      byte[] info = tlv(sm.getOptionalParameters(), OptionalParameter.Tag.ITS_SESSION_INFO);
      if (info != null && info.length >= 2) {
        boolean end = (info[1] & 1) == 1 || (op != null && (op[0] & 0xFF) == PSSR_RESPONSE);
        BlockingQueue<Screen> q = waiting.get(info[0] & 0xFF);
        if (q != null) {
          q.add(new Screen(decode(sm.getDataCoding(), sm.getShortMessage()), end));
        }
      }
      try {
        return new SubmitSmResult(new MessageId("USSD-" + Integer.toHexString(sessionNumbers.get())), null);
      } catch (Exception e) {
        throw new ProcessRequestException("message id", SMPPConstant.STAT_ESME_RSYSERR);
      }
    }

    private boolean take(AtomicInteger counter) {
      if (counter.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
        refused.incrementAndGet();
        return true;
      }
      return false;
    }

    @Override
    public SubmitMultiResult onAcceptSubmitMulti(SubmitMulti s, SMPPServerSession src) throws ProcessRequestException {
      throw unsupported();
    }

    @Override
    public QuerySmResult onAcceptQuerySm(QuerySm q, SMPPServerSession src) throws ProcessRequestException {
      throw unsupported();
    }

    @Override
    public void onAcceptReplaceSm(ReplaceSm r, SMPPServerSession src) throws ProcessRequestException {
      throw unsupported();
    }

    @Override
    public void onAcceptCancelSm(CancelSm c, SMPPServerSession src) throws ProcessRequestException {
      throw unsupported();
    }

    @Override
    public BroadcastSmResult onAcceptBroadcastSm(BroadcastSm b, SMPPServerSession src) throws ProcessRequestException {
      throw unsupported();
    }

    @Override
    public void onAcceptCancelBroadcastSm(CancelBroadcastSm c, SMPPServerSession src) throws ProcessRequestException {
      throw unsupported();
    }

    @Override
    public QueryBroadcastSmResult onAcceptQueryBroadcastSm(QueryBroadcastSm q, SMPPServerSession src)
        throws ProcessRequestException {
      throw unsupported();
    }

    @Override
    public DataSmResult onAcceptDataSm(DataSm d, Session src) throws ProcessRequestException {
      throw unsupported();
    }

    private ProcessRequestException unsupported() {
      return new ProcessRequestException("not supported by the simulator", SMPPConstant.STAT_ESME_RINVCMDID);
    }
  }
}

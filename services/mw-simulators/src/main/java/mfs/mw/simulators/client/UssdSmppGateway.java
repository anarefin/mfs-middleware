package mfs.mw.simulators.client;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
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
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.service.mockcontrol.Decision;
import mfs.mw.simulators.service.mockcontrol.Journal;
import mfs.mw.simulators.service.mockcontrol.MockCalls;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import mfs.mw.simulators.service.mockcontrol.MockParties;
import org.jsmpp.DefaultPDUReader;
import org.jsmpp.DefaultPDUSender;
import org.jsmpp.SMPPConstant;
import org.jsmpp.SynchronizedPDUSender;
import org.jsmpp.bean.BindType;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The SMPP side of the USSD gateway simulator (spec 22.2, ticket 27, 109a): one SMPP 3.4 server per
 * MNO port. Grameenphone listens on {@code sim.ussd-gateway.smpp.port}; robi, bl and teletalk listen
 * when {@code peers-enabled} is set. mw-ussd may bind as transceiver or as transmitter + receiver
 * (a {@code deliver_sm} is sent on a receiver or transceiver, never on a transmitter). {@link #play}
 * plays a subscriber on that MNO's port: {@code ussd_service_op} 1 then 18, with {@code
 * its_session_info} packed for the MNO ({@link UssdSessionInfo}). A screen is END when
 * {@code ussd_service_op} is 17 or the packing's end bit is set. Faults come from the port's mock
 * party ({@code ussd-gp}, {@code ussd-robi}, {@code ussd-bl}, {@code ussd-teletalk}); the
 * Grameenphone port also honours a scenario on the legacy party {@code ussdgw} when its own party
 * has none. {@link #drop} closes every bind. {@link #binds} reports the Grameenphone port.
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

  /** A bound session and the bind it accepted. */
  private record Bound(SMPPServerSession session, BindType type) {}

  /** The legacy mock party. Grameenphone falls back to it; dial stays here. */
  public static final String PARTY = "ussdgw";

  private final UssdSmppGatewayProperties props;
  private final MockControl control;
  private final ObjectProvider<Journal> journal;
  private final Clock clock;
  private final List<Port> ports = new CopyOnWriteArrayList<>();
  private volatile boolean running;

  public UssdSmppGateway(
      UssdSmppGatewayProperties props, MockControl control, ObjectProvider<Journal> journal, Clock clock) {
    this.props = props;
    this.control = control;
    this.journal = journal;
    this.clock = clock;
  }

  /** Whether {@code mno} is played over SMPP. */
  public boolean plays(String mno) {
    return props.mnos().contains(mno);
  }

  @Override
  public synchronized void start() {
    if (!props.enabled() || running) {
      return;
    }
    ports.add(new Port("gp", props.port(), props.party(), props.packing()));
    if (props.peersEnabled()) {
      props.peers().forEach((mno, peer) -> {
        if (peer.listen()) {
          String party = peer.party() == null || peer.party().isBlank() ? "ussd-" + mno : peer.party();
          ports.add(new Port(mno, peer.port(), party, peer.packing()));
        }
      });
    }
    ports.forEach(Port::start);
    running = true;
  }

  @Override
  public synchronized void stop() {
    running = false;
    ports.forEach(Port::stop);
    ports.clear();
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** Closes every bind on every port, as a gateway restart would; mw-ussd reconnects. */
  public void drop() {
    ports.forEach(Port::drop);
  }

  /** Binds of the Grameenphone port (the {@code /sim/ussdgw/smpp/binds} report). */
  public UssdSmppBinds binds() {
    Port gp = port("gp");
    return gp == null ? new UssdSmppBinds(0, 0, 0, 0) : gp.binds();
  }

  /**
   * Plays one session on the MNO's port, on the next bound receiver or transceiver. A step without
   * a screen within the screen timeout (or without any bind) is a screen of status -1.
   */
  public UssdDialResult play(UssdDialRequest req) {
    Port port = port(req.mno());
    if (port == null) {
      return new UssdDialResult("smpp-0", List.of(new UssdScreen(-1, null, null)), false);
    }
    return port.play(req);
  }

  private Port port(String mno) {
    return ports.stream().filter(p -> p.mno.equals(mno)).findFirst().orElse(null);
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

  private static UssdScreen screen(Screen s) {
    return s == null ? new UssdScreen(-1, null, null) : new UssdScreen(200, s.text(), s.end() ? "END" : "CON");
  }

  /** One MNO's SMPP listener. */
  private final class Port {

    private final String mno;
    private final int port;
    private final String party;
    private final String packing;
    private final List<Bound> sessions = new CopyOnWriteArrayList<>();
    private final Map<Integer, BlockingQueue<Screen>> waiting = new ConcurrentHashMap<>();
    private final AtomicInteger binds = new AtomicInteger();
    private final AtomicInteger enquireLinks = new AtomicInteger();
    private final AtomicInteger refused = new AtomicInteger();
    private final AtomicInteger sessionNumbers = new AtomicInteger();
    private final AtomicInteger cursor = new AtomicInteger();
    private volatile boolean open;
    private ServerConnection server;

    Port(String mno, int port, String party, String packing) {
      this.mno = mno;
      this.port = port;
      this.party = party;
      this.packing = packing;
    }

    void start() {
      try {
        server = new ServerSocketConnectionFactory().listen(port);
      } catch (IOException e) {
        throw new IllegalStateException("USSD SMPP gateway cannot listen on " + port, e);
      }
      open = true;
      Thread.ofPlatform().name("ussd-smpp-" + mno).daemon().start(this::acceptLoop);
      log.atInfo().addKeyValue("event", "sim.ussd.smpp.listening").addKeyValue("port", port)
          .addKeyValue("mno", mno).log("USSD SMPP gateway simulator listening");
    }

    void stop() {
      open = false;
      drop();
      if (server != null) {
        try {
          server.close();
        } catch (IOException e) {
          log.atWarn().addKeyValue("event", "sim.ussd.smpp.close_failed").addKeyValue("mno", mno)
              .log("listener did not close cleanly");
        }
      }
    }

    /** The party's timeout, and for {@code ussd-gp} the legacy {@code ussdgw} timeout, else the config. */
    private Duration screenTimeout() {
      Long ms = control.settings(party).timeoutDelayMs();
      if (ms == null && "ussd-gp".equals(party)) {
        ms = control.settings(PARTY).timeoutDelayMs();
      }
      return ms == null ? props.screenTimeout() : Duration.ofMillis(ms);
    }

    private void record(String what, Map<String, String> headers) {
      journal.ifAvailable(j -> j.record(party, "SMPP", what, null, headers, null, clock.instant()));
    }

    /**
     * The port's party, or the legacy {@code ussdgw} party when Grameenphone's own party was not
     * told to do anything (so the old scenarios and the test console keep working).
     */
    private Decision fault(String operation, Map<String, String> keys) {
      Decision own = control.decide(party, operation, keys, SimMode.SUCCESS);
      if ("ussd-gp".equals(party) && untouched(own)) {
        Decision legacy = control.decide(PARTY, operation, keys, SimMode.SUCCESS);
        if (!untouched(legacy)) {
          return legacy;
        }
      }
      return own;
    }

    private static boolean untouched(Decision d) {
      return d.gate() == Decision.Gate.OPEN && d.mode() == SimMode.SUCCESS && "default".equals(d.source())
          && d.delayMs() == 0;
    }

    void drop() {
      List<Bound> all = new ArrayList<>(sessions);
      sessions.clear();
      all.forEach(b -> b.session().close());
      log.atInfo().addKeyValue("event", "sim.ussd.smpp.dropped").addKeyValue("mno", mno)
          .addKeyValue("binds", all.size()).log("USSD SMPP binds dropped");
    }

    UssdSmppBinds binds() {
      int bound = (int) sessions.stream().filter(b -> b.session().getSessionState().isBound()).count();
      return new UssdSmppBinds(bound, binds.get(), enquireLinks.get(), refused.get());
    }

    UssdDialResult play(UssdDialRequest req) {
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

    private SMPPServerSession next() {
      List<Bound> up = sessions.stream()
          .filter(b -> b.session().getSessionState().isBound() && b.type() != BindType.BIND_TX)
          .toList();
      return up.isEmpty() ? null : up.get(Math.floorMod(cursor.getAndIncrement(), up.size())).session();
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
                UssdSessionInfo.pack(packing, number, seq, false)));
        return queue.poll(screenTimeout().toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      } catch (Exception e) {
        log.atWarn().addKeyValue("event", "sim.ussd.smpp.deliver_failed").addKeyValue("mno", mno)
            .addKeyValue("errorClass", e.getClass().getName()).log("deliver_sm not accepted");
        return null;
      }
    }

    private void acceptLoop() {
      while (open) {
        try {
          Connection conn = server.accept();
          conn.setSoTimeout(5000);
          SMPPServerSession session = new SMPPServerSession(conn, null, new Receiver(), null, 3, 100,
              new SynchronizedPDUSender(new Sender()), new DefaultPDUReader());
          Thread.ofPlatform().daemon().name("ussd-smpp-bind-" + mno).start(() -> bind(session));
        } catch (IOException e) {
          if (open) {
            log.atWarn().addKeyValue("event", "sim.ussd.smpp.accept_failed").addKeyValue("mno", mno)
                .log("SMPP accept failed");
          }
        }
      }
    }

    private void bind(SMPPServerSession session) {
      try {
        BindRequest bind = session.waitForBind(5000);
        String systemId = bind.getSystemId() == null ? "" : bind.getSystemId();
        Decision d = fault("bind", Map.of("systemId", systemId));
        record("bind", Map.of("systemId", systemId));
        MockCalls.pause(d.delayMs());
        if (d.gate() == Decision.Gate.DOWN || d.mode() == SimMode.CONNECTION_RESET) {
          session.close();
          return;
        }
        if (d.gate() == Decision.Gate.MAINTENANCE || d.mode() == SimMode.BUSINESS_FAILURE) {
          int status = d.code() == null ? SMPPConstant.STAT_ESME_RBINDFAIL : MockParties.smppStatus(d.code());
          bind.reject(status);
          log.atInfo().addKeyValue("event", "sim.ussd.smpp.bind_refused").addKeyValue("mno", mno)
              .addKeyValue("commandStatus", status).log("USSD SMPP bind refused (mock control)");
          return;
        }
        bind.accept("bits-ussdgw");
        binds.incrementAndGet();
        sessions.add(new Bound(session, bind.getBindType()));
        log.atInfo().addKeyValue("event", "sim.ussd.smpp.bound").addKeyValue("mno", mno)
            .addKeyValue("bindType", bind.getBindType()).log("USSD SMPP client bound");
      } catch (Exception e) {
        log.atWarn().addKeyValue("event", "sim.ussd.smpp.bind_failed").addKeyValue("mno", mno)
            .addKeyValue("errorClass", e.getClass().getName()).log("USSD SMPP bind failed");
        session.close();
      }
    }

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
        Decision d = fault("enquire_link", Map.of());
        MockCalls.pause(d.delayMs());
        boolean answer = d.gate() == Decision.Gate.OPEN && d.mode() == SimMode.SUCCESS;
        return answer ? super.sendEnquireLinkResp(os, sequenceNumber) : new byte[0];
      }
    }

    private final class Receiver implements ServerMessageReceiverListener {

      @Override
      public SubmitSmResult onAcceptSubmitSm(SubmitSm sm, SMPPServerSession session) throws ProcessRequestException {
        String subscriber = sm.getDestAddress() == null ? "" : sm.getDestAddress();
        Decision d = fault("submit", Map.of("msisdn", subscriber));
        record("submit_sm", Map.of("destination", subscriber));
        MockCalls.pause(d.delayMs());
        if (d.gate() == Decision.Gate.DOWN) {
          session.close();
          throw new ProcessRequestException("connection closed (mock control)", SMPPConstant.STAT_ESME_RSYSERR);
        }
        if (d.gate() == Decision.Gate.MAINTENANCE) {
          throw refuse("maintenance", SMPPConstant.STAT_ESME_RSYSERR);
        }
        if (d.mode() == SimMode.BUSINESS_FAILURE) {
          throw refuse("refused", d.code() == null ? SMPPConstant.STAT_ESME_RTHROTTLED : MockParties.smppStatus(d.code()));
        }
        if (d.mode() == SimMode.MALFORMED) {
          throw refuse("generic_nack", NACK_MARKER);
        }
        byte[] op = tlv(sm.getOptionalParameters(), OptionalParameter.Tag.USSD_SERVICE_OP);
        byte[] info = tlv(sm.getOptionalParameters(), OptionalParameter.Tag.ITS_SESSION_INFO);
        if (info != null && info.length >= 2) {
          UssdSessionInfo.Fields fields = UssdSessionInfo.read(packing, info);
          boolean end = fields.end() || (op != null && (op[0] & 0xFF) == PSSR_RESPONSE);
          BlockingQueue<Screen> q = waiting.get(fields.session());
          if (q != null) {
            q.add(new Screen(decode(sm.getDataCoding(), sm.getShortMessage()), end));
          }
        }
        if (d.mode() == SimMode.CONNECTION_RESET) {
          session.close();
          throw new ProcessRequestException("connection closed (mock control)", SMPPConstant.STAT_ESME_RSYSERR);
        }
        if (d.mode() == SimMode.TIMEOUT) {
          MockCalls.pause(d.holdMs());
        }
        try {
          return new SubmitSmResult(new MessageId("USSD-" + Integer.toHexString(sessionNumbers.get())), null);
        } catch (Exception e) {
          throw new ProcessRequestException("message id", SMPPConstant.STAT_ESME_RSYSERR);
        }
      }

      private ProcessRequestException refuse(String why, int status) {
        refused.incrementAndGet();
        return new ProcessRequestException(why + " (mock control)", status);
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
}

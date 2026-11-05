package mfs.mw.ussd.client;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

/**
 * A minimal in-test MNO USSD gateway (SMPP 3.4 server on jSMPP): accepts any bind, plays a
 * subscriber with {@link #dial} ({@code deliver_sm} with {@code ussd_service_op} and {@code
 * its_session_info}, then waits for the {@code submit_sm} screen of that session), and misbehaves
 * on demand: refuse the next submits with 0x58 / 0x14 / {@code generic_nack}, stop answering
 * {@code enquire_link}, drop every bind. The module's own stand-in for the USSD gateway simulator
 * in mw-simulators (no dependency on another module, spec 4.3).
 */
public final class UssdGatewayStub implements AutoCloseable {

  /** command_status the receiver throws to make the PDU sender answer with a generic_nack. */
  private static final int NACK_MARKER = 0x0400;

  /** One screen mw-ussd sent: the submit_sm as received, and on which bind (accept order). */
  public record Screen(int bindIndex, String address, String serviceCode, String serviceType, int serviceOp,
      int sessionNumber, int sequence, boolean endOfSession, String text) {}

  private final ServerConnection server;
  private final int port;
  private final List<SMPPServerSession> sessions = new CopyOnWriteArrayList<>();
  private final List<Screen> screens = new CopyOnWriteArrayList<>();
  private final Map<Integer, BlockingQueue<Screen>> waiting = new ConcurrentHashMap<>();
  private final AtomicInteger binds = new AtomicInteger();
  private final AtomicInteger enquireLinks = new AtomicInteger();
  private final AtomicInteger throttle = new AtomicInteger();
  private final AtomicInteger queueFull = new AtomicInteger();
  private final AtomicInteger genericNack = new AtomicInteger();
  private final AtomicInteger refused = new AtomicInteger();
  private final AtomicInteger sessionNumbers = new AtomicInteger();
  private final AtomicBoolean silent = new AtomicBoolean();
  private volatile boolean running = true;

  public UssdGatewayStub() throws IOException {
    int free;
    try (ServerSocket s = new ServerSocket(0)) {
      free = s.getLocalPort();
    }
    port = free;
    server = new ServerSocketConnectionFactory().listen(port);
    Thread.ofPlatform().daemon().name("ussd-stub-accept").start(this::acceptLoop);
  }

  public int port() {
    return port;
  }

  /** Bind requests accepted so far (re-binds count again). */
  public int bindCount() {
    return binds.get();
  }

  public int boundSessions() {
    return (int) sessions.stream().filter(s -> s.getSessionState().isBound()).count();
  }

  public int enquireLinkCount() {
    return enquireLinks.get();
  }

  /** submit_sm refused so far (any reason). */
  public int refusedCount() {
    return refused.get();
  }

  public List<Screen> screens() {
    return List.copyOf(screens);
  }

  public void throttleNext(int n) {
    throttle.set(n);
  }

  public void queueFullNext(int n) {
    queueFull.set(n);
  }

  public void genericNackNext(int n) {
    genericNack.set(n);
  }

  /** Stops (or resumes) answering enquire_link. */
  public void silent(boolean on) {
    silent.set(on);
  }

  /** Closes every bound session, as a gateway restart would. */
  public void dropBinds() {
    List<SMPPServerSession> all = new ArrayList<>(sessions);
    sessions.clear();
    all.forEach(SMPPServerSession::close);
  }

  /**
   * Plays one subscriber session on bind {@code bindIndex} (0 = first bound): dials {@code
   * serviceCode}, then answers each screen with the next input while the session is open. Returns
   * the screens in order.
   */
  public List<Screen> dial(int bindIndex, String address, String serviceCode, List<String> inputs) {
    int number = sessionNumbers.incrementAndGet() & 0xFF;
    BlockingQueue<Screen> queue = new LinkedBlockingQueue<>();
    waiting.put(number, queue);
    try {
      List<Screen> shown = new ArrayList<>();
      Screen s = deliverAndWait(bindIndex, address, serviceCode, 1, number, 0, false, serviceCode, queue);
      shown.add(s);
      int seq = 1;
      for (String input : inputs) {
        if (s == null || s.endOfSession()) {
          break;
        }
        seq += 2;
        s = deliverAndWait(bindIndex, address, serviceCode, 18, number, seq, false, input, queue);
        shown.add(s);
      }
      return Collections.unmodifiableList(shown);
    } finally {
      waiting.remove(number);
    }
  }

  /** The subscriber hangs up session {@code number}: a deliver_sm with end-of-session set. */
  public void release(int bindIndex, String address, String serviceCode, int number) {
    deliver(bound(bindIndex), address, serviceCode, 18, number, 127, true, "");
  }

  private Screen deliverAndWait(int bindIndex, String address, String serviceCode, int op, int number, int seq,
      boolean end, String text, BlockingQueue<Screen> queue) {
    deliver(bound(bindIndex), address, serviceCode, op, number, seq, end, text);
    try {
      return queue.poll(15, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  private SMPPServerSession bound(int index) {
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (System.nanoTime() < deadline) {
      List<SMPPServerSession> up = sessions.stream().filter(s -> s.getSessionState().isBound()).toList();
      if (up.size() > index) {
        return up.get(index);
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    throw new IllegalStateException("no bound session " + index);
  }

  private static void deliver(SMPPServerSession session, String address, String serviceCode, int op, int number,
      int seq, boolean end, String text) {
    boolean ucs2 = !text.chars().allMatch(c -> c < 0x80);
    byte[] body = ucs2 ? text.getBytes(StandardCharsets.UTF_16BE) : text.getBytes(StandardCharsets.US_ASCII);
    try {
      session.deliverShortMessage("USSD", TypeOfNumber.INTERNATIONAL, NumberingPlanIndicator.ISDN, address,
          TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, serviceCode, new ESMClass(), (byte) 0, (byte) 0,
          new RegisteredDelivery(0), ucs2 ? DataCodings.newInstance((byte) 8) : DataCodings.ZERO, body,
          new OptionalParameter.OctetString(OptionalParameter.Tag.USSD_SERVICE_OP.code(), new byte[] {(byte) op}),
          new OptionalParameter.OctetString(OptionalParameter.Tag.ITS_SESSION_INFO.code(),
              new byte[] {(byte) number, (byte) ((seq << 1) | (end ? 1 : 0))}));
    } catch (Exception e) {
      throw new IllegalStateException("deliver_sm failed", e);
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

  private void acceptLoop() {
    while (running) {
      try {
        Connection conn = server.accept();
        conn.setSoTimeout(5000);
        SMPPServerSession session = new SMPPServerSession(conn, null, new Receiver(), null, 3, 100,
            new SynchronizedPDUSender(new Sender()), new DefaultPDUReader());
        Thread.ofPlatform().daemon().start(() -> bind(session));
      } catch (IOException e) {
        if (!running) {
          return;
        }
      }
    }
  }

  private void bind(SMPPServerSession session) {
    try {
      BindRequest bind = session.waitForBind(5000);
      bind.accept("ussd-stub");
      binds.incrementAndGet();
      sessions.add(session);
    } catch (Exception e) {
      session.close();
    }
  }

  @Override
  public void close() throws IOException {
    running = false;
    dropBinds();
    server.close();
  }

  /** Answers like the default sender, except: generic_nack on demand, no enquire_link_resp when silent. */
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
      if (silent.get()) {
        return new byte[0];
      }
      return super.sendEnquireLinkResp(os, sequenceNumber);
    }
  }

  private final class Receiver implements ServerMessageReceiverListener {

    @Override
    public SubmitSmResult onAcceptSubmitSm(SubmitSm sm, SMPPServerSession session) throws ProcessRequestException {
      if (takeOne(genericNack)) {
        throw new ProcessRequestException("generic_nack", NACK_MARKER);
      }
      if (takeOne(throttle)) {
        throw new ProcessRequestException("throttled", SMPPConstant.STAT_ESME_RTHROTTLED);
      }
      if (takeOne(queueFull)) {
        throw new ProcessRequestException("queue full", SMPPConstant.STAT_ESME_RMSGQFUL);
      }
      byte[] op = tlv(sm.getOptionalParameters(), OptionalParameter.Tag.USSD_SERVICE_OP);
      byte[] info = tlv(sm.getOptionalParameters(), OptionalParameter.Tag.ITS_SESSION_INFO);
      String text = mfs.mw.ussd.util.UssdText.decode(sm.getDataCoding(), sm.getShortMessage());
      int number = info == null ? -1 : info[0] & 0xFF;
      Screen s = new Screen(sessions.indexOf(session), sm.getDestAddress(), sm.getSourceAddr(), sm.getServiceType(),
          op == null ? -1 : op[0] & 0xFF, number, info == null ? -1 : (info[1] & 0xFF) >> 1,
          info != null && (info[1] & 1) == 1, text);
      screens.add(s);
      BlockingQueue<Screen> q = waiting.get(number);
      if (q != null) {
        q.add(s);
      }
      try {
        return new SubmitSmResult(new MessageId("ussd-" + screens.size()), null);
      } catch (Exception e) {
        throw new ProcessRequestException("id", SMPPConstant.STAT_ESME_RSYSERR);
      }
    }

    private boolean takeOne(AtomicInteger counter) {
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
      return new ProcessRequestException("not supported", SMPPConstant.STAT_ESME_RINVCMDID);
    }
  }
}

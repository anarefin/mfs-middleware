package mfs.mw.notify.client;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jsmpp.SMPPConstant;
import org.jsmpp.bean.BroadcastSm;
import org.jsmpp.bean.CancelBroadcastSm;
import org.jsmpp.bean.CancelSm;
import org.jsmpp.bean.DataCodings;
import org.jsmpp.bean.DataSm;
import org.jsmpp.bean.DeliveryReceipt;
import org.jsmpp.bean.ESMClass;
import org.jsmpp.bean.GSMSpecificFeature;
import org.jsmpp.bean.MessageMode;
import org.jsmpp.bean.MessageType;
import org.jsmpp.bean.NumberingPlanIndicator;
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
import org.jsmpp.session.SMPPServerSessionListener;
import org.jsmpp.session.ServerMessageReceiverListener;
import org.jsmpp.session.Session;
import org.jsmpp.session.SubmitMultiResult;
import org.jsmpp.session.SubmitSmResult;
import org.jsmpp.util.DeliveryReceiptState;
import org.jsmpp.util.MessageId;

/**
 * A minimal in-test SMSC (SMPP 3.4 server on jSMPP): accepts any bind, answers {@code submit_sm}
 * with a message_id and sends a DELIVRD receipt on the same session shortly after. {@link
 * #throttle} makes the next submits to a number fail with ESME_RTHROTTLED (0x58), {@link #refuse}
 * with any other command status. The module's own
 * stand-in for the SMSC simulator in mw-simulators (no dependency on another module, spec 4.3).
 */
public final class SmscStub implements AutoCloseable {

  /** One accepted submit_sm. */
  public record Received(String destination, String source, byte dataCoding, byte esmClass, byte[] shortMessage,
      String messageId) {}

  private final SMPPServerSessionListener listener;
  private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
  private final List<Received> received = Collections.synchronizedList(new ArrayList<>());
  private record Refusal(AtomicInteger left, int status) {}

  private final Map<String, Refusal> refusals = new ConcurrentHashMap<>();
  private final AtomicInteger ids = new AtomicInteger();
  private final AtomicInteger binds = new AtomicInteger();
  private volatile boolean running = true;

  public SmscStub() throws IOException {
    int port;
    try (ServerSocket s = new ServerSocket(0)) {
      port = s.getLocalPort();
    }
    listener = new SMPPServerSessionListener(port);
    listener.setMessageReceiverListener(new Receiver());
    executor.execute(this::acceptLoop);
  }

  public int port() {
    return listener.getPort();
  }

  public List<Received> received() {
    return List.copyOf(received);
  }

  public List<Received> receivedFor(String destination) {
    return received().stream().filter(r -> r.destination().equals(destination)).toList();
  }

  public int bindCount() {
    return binds.get();
  }

  /** The next {@code times} submits to {@code destination} are refused with 0x58. */
  public void throttle(String destination, int times) {
    refuse(destination, times, SMPPConstant.STAT_ESME_RTHROTTLED);
  }

  /** The next {@code times} submits to {@code destination} are refused with {@code commandStatus}. */
  public void refuse(String destination, int times, int commandStatus) {
    refusals.put(destination, new Refusal(new AtomicInteger(times), commandStatus));
  }

  /** Text of a single-part message as GSM-7 (ASCII subset) or UCS-2. */
  public static String text(Received r) {
    return r.dataCoding() == 0x08
        ? new String(r.shortMessage(), StandardCharsets.UTF_16BE)
        : new String(r.shortMessage(), StandardCharsets.US_ASCII);
  }

  private void acceptLoop() {
    while (running) {
      try {
        SMPPServerSession session = listener.accept();
        BindRequest bind = session.waitForBind(5000);
        bind.accept("stub-smsc");
        binds.incrementAndGet();
      } catch (Exception e) {
        if (!running) {
          return;
        }
      }
    }
  }

  @Override
  public void close() throws IOException {
    running = false;
    listener.close();
    executor.shutdownNow();
  }

  private final class Receiver implements ServerMessageReceiverListener {

    @Override
    public SubmitSmResult onAcceptSubmitSm(SubmitSm sm, SMPPServerSession session) throws ProcessRequestException {
      Refusal refusal = refusals.get(sm.getDestAddress());
      if (refusal != null && refusal.left().getAndDecrement() > 0) {
        throw new ProcessRequestException("refused", refusal.status());
      }
      String id = "stub-" + ids.incrementAndGet();
      received.add(new Received(sm.getDestAddress(), sm.getSourceAddr(), sm.getDataCoding(), sm.getEsmClass(),
          sm.getShortMessage(), id));
      executor.schedule(() -> deliveryReceipt(session, sm, id), 100, TimeUnit.MILLISECONDS);
      try {
        return new SubmitSmResult(new MessageId(id), null);
      } catch (Exception e) {
        throw new ProcessRequestException("bad id", SMPPConstant.STAT_ESME_RSYSERR);
      }
    }

    private void deliveryReceipt(SMPPServerSession session, SubmitSm sm, String id) {
      Date now = new Date();
      DeliveryReceipt receipt = new DeliveryReceipt(id, 1, 1, now, now, DeliveryReceiptState.DELIVRD, "000", "");
      try {
        session.deliverShortMessage("", TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, sm.getDestAddress(),
            TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, sm.getSourceAddr(),
            new ESMClass(MessageMode.DEFAULT, MessageType.SMSC_DEL_RECEIPT, GSMSpecificFeature.DEFAULT),
            (byte) 0, (byte) 0, new RegisteredDelivery(0), DataCodings.ZERO,
            receipt.toString().getBytes(StandardCharsets.US_ASCII));
      } catch (Exception e) {
        // the bind went away; a real SMSC would retry later
      }
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
      return new ProcessRequestException("unsupported", SMPPConstant.STAT_ESME_RINVCMDID);
    }
  }
}

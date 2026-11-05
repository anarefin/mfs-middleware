package mfs.mw.simulators.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import mfs.mw.simulators.config.SmscProperties;
import mfs.mw.simulators.dto.SmscDlrState;
import mfs.mw.simulators.exception.SmscRefusalException;
import mfs.mw.simulators.service.SmscService;
import org.jsmpp.PDUStringException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * SMPP 3.4 front of the SMSC simulator (spec 22.2, ticket 24), on jSMPP: accepts any bind (test
 * only; the simulator does not check credentials), hands each {@code submit_sm} to {@link
 * SmscService} and, when the service asks for one, sends the delivery receipt ({@code deliver_sm},
 * esm_class SMSC delivery receipt, {@code id:<message_id> ... stat:<state>}) on the same session
 * after {@code sim.smsc.dlr-delay}. Refusals become the submit_sm_resp command_status.
 */
@Component
@ConditionalOnProperty(name = "sim.smsc.enabled", havingValue = "true", matchIfMissing = true)
public class SmscServer implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(SmscServer.class);

  private final SmscService smsc;
  private final SmscProperties props;
  private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
  private SMPPServerSessionListener listener;
  private volatile boolean running;

  public SmscServer(SmscService smsc, SmscProperties props) {
    this.smsc = smsc;
    this.props = props;
  }

  @Override
  public synchronized void start() {
    try {
      listener = new SMPPServerSessionListener(props.port());
    } catch (IOException e) {
      throw new IllegalStateException("SMSC simulator cannot listen on " + props.port(), e);
    }
    listener.setMessageReceiverListener(new Receiver());
    running = true;
    Thread.ofPlatform().name("smsc-accept").daemon().start(this::acceptLoop);
    log.atInfo().addKeyValue("event", "sim.smsc.listening").addKeyValue("port", props.port())
        .log("SMSC simulator listening");
  }

  @Override
  public synchronized void stop() {
    running = false;
    try {
      if (listener != null) {
        listener.close();
      }
    } catch (IOException e) {
      log.atWarn().addKeyValue("event", "sim.smsc.close_failed").log("SMSC listener did not close cleanly");
    }
    executor.shutdownNow();
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  private void acceptLoop() {
    while (running) {
      try {
        SMPPServerSession session = listener.accept();
        executor.execute(() -> bind(session));
      } catch (IOException e) {
        if (running) {
          log.atWarn().addKeyValue("event", "sim.smsc.accept_failed").log("SMPP accept failed");
        }
      }
    }
  }

  private void bind(SMPPServerSession session) {
    try {
      BindRequest bind = session.waitForBind(5000);
      bind.accept("upay-smsc-sim");
      log.atInfo().addKeyValue("event", "sim.smsc.bound").addKeyValue("bindType", bind.getBindType())
          .log("SMPP client bound");
    } catch (Exception e) {
      log.atWarn().addKeyValue("event", "sim.smsc.bind_failed").addKeyValue("errorClass", e.getClass().getName())
          .log("SMPP bind failed");
      session.close();
    }
  }

  private void deliveryReceipt(SMPPServerSession session, SubmitSm sm, String id, SmscDlrState state) {
    Date now = new Date();
    DeliveryReceiptState stat = DeliveryReceiptState.valueOf(state.name());
    String err = state == SmscDlrState.DELIVRD ? "000" : "001";
    DeliveryReceipt receipt = new DeliveryReceipt(id, 1, state == SmscDlrState.DELIVRD ? 1 : 0, now, now, stat, err, "");
    try {
      session.deliverShortMessage("", TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, sm.getDestAddress(),
          TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, sm.getSourceAddr(),
          new ESMClass(MessageMode.DEFAULT, MessageType.SMSC_DEL_RECEIPT, GSMSpecificFeature.DEFAULT),
          (byte) 0, (byte) 0, new RegisteredDelivery(0), DataCodings.ZERO,
          receipt.toString().getBytes(StandardCharsets.US_ASCII));
      smsc.dlrSent(id, state);
    } catch (Exception e) {
      log.atWarn().addKeyValue("event", "sim.smsc.dlr_failed").addKeyValue("errorClass", e.getClass().getName())
          .log("delivery receipt not sent");
    }
  }

  private final class Receiver implements ServerMessageReceiverListener {

    @Override
    public SubmitSmResult onAcceptSubmitSm(SubmitSm sm, SMPPServerSession session) throws ProcessRequestException {
      SmscService.Submission s;
      try {
        s = smsc.submit(sm.getDestAddress(), sm.getSourceAddr(), sm.getDataCoding(), sm.getEsmClass(),
            sm.getShortMessage());
      } catch (SmscRefusalException e) {
        throw new ProcessRequestException(e.getMessage(), e.commandStatus());
      }
      if (s.dlrState() != null) {
        executor.schedule(() -> deliveryReceipt(session, sm, s.messageId(), s.dlrState()),
            props.dlrDelay().toMillis(), TimeUnit.MILLISECONDS);
      }
      try {
        return new SubmitSmResult(new MessageId(s.messageId()), null);
      } catch (PDUStringException e) {
        throw new ProcessRequestException("message id", SMPPConstant.STAT_ESME_RSYSERR);
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
      return new ProcessRequestException("not supported by the simulator", SMPPConstant.STAT_ESME_RINVCMDID);
    }
  }
}

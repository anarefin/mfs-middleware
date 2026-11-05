package mfs.mw.notify.client;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import mfs.mw.notify.config.SmppProperties;
import mfs.mw.notify.dto.DeliveryReport;
import mfs.mw.notify.dto.DlrOutcome;
import mfs.mw.notify.dto.Priority;
import mfs.mw.notify.dto.SmsPart;
import mfs.mw.notify.dto.SubmitResult;
import mfs.mw.notify.exception.SmsSendException;
import org.jsmpp.InvalidResponseException;
import org.jsmpp.PDUException;
import org.jsmpp.SMPPConstant;
import org.jsmpp.bean.AlertNotification;
import org.jsmpp.bean.DataCodings;
import org.jsmpp.bean.DataSm;
import org.jsmpp.bean.DeliverSm;
import org.jsmpp.bean.DeliveryReceipt;
import org.jsmpp.bean.ESMClass;
import org.jsmpp.bean.GSMSpecificFeature;
import org.jsmpp.bean.MessageMode;
import org.jsmpp.bean.MessageType;
import org.jsmpp.bean.NumberingPlanIndicator;
import org.jsmpp.bean.RegisteredDelivery;
import org.jsmpp.bean.SMSCDeliveryReceipt;
import org.jsmpp.bean.TypeOfNumber;
import org.jsmpp.extra.NegativeResponseException;
import org.jsmpp.extra.ProcessRequestException;
import org.jsmpp.extra.ResponseTimeoutException;
import org.jsmpp.session.DataSmResult;
import org.jsmpp.session.MessageReceiverListener;
import org.jsmpp.session.SMPPSession;
import org.jsmpp.session.Session;
import org.jsmpp.util.DeliveryReceiptState;
import org.jsmpp.util.InvalidDeliveryReceiptException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * SMPP 3.4 client to the SMSC over jSMPP (spec 10.2, 9.2.1): a pool of transceiver binds per
 * priority, round-robin, so each attempt can go out on another bind (10.4). {@code submit_sm}
 * asks for a delivery receipt; the binds' {@code deliver_sm} receipts go to the {@link
 * DeliveryReportHandler}. Throttling (0x58), queue full (0x14), system error (0x08), timeouts,
 * I/O errors and no bound session are transient; other command statuses are permanent.
 */
@Component
@ConditionalOnProperty(name = "mw.notify.smpp.enabled", havingValue = "true", matchIfMissing = true)
public class SmppSmsSender implements SmsSender {

  private static final Logger log = LoggerFactory.getLogger(SmppSmsSender.class);

  private static final Set<Integer> TRANSIENT_STATUSES =
      Set.of(SMPPConstant.STAT_ESME_RTHROTTLED, SMPPConstant.STAT_ESME_RMSGQFUL, SMPPConstant.STAT_ESME_RSYSERR);

  private final Map<Priority, List<SmppBind>> pools = new EnumMap<>(Priority.class);
  private final Map<Priority, AtomicInteger> cursors = new EnumMap<>(Priority.class);

  public SmppSmsSender(SmppProperties props, DeliveryReportHandler handler, Clock clock) {
    MessageReceiverListener receiver = new Receiver(handler);
    for (Priority p : Priority.values()) {
      List<SmppBind> binds = new ArrayList<>();
      for (int i = 1; i <= props.binds().get(p); i++) {
        binds.add(new SmppBind(p.name().toLowerCase(Locale.ROOT) + "-" + i, props, receiver, clock));
      }
      pools.put(p, List.copyOf(binds));
      cursors.put(p, new AtomicInteger());
    }
  }

  @Override
  public SubmitResult submit(Priority priority, SmsPart part) throws SmsSendException {
    List<SmppBind> pool = pools.get(priority);
    int start = Math.floorMod(cursors.get(priority).getAndIncrement(), pool.size());
    for (int i = 0; i < pool.size(); i++) {
      SmppBind bind = pool.get((start + i) % pool.size());
      SMPPSession session = bind.bound();
      if (session != null) {
        return submitOn(bind, session, part);
      }
    }
    throw new SmsSendException(true, -1, null, "no SMPP bind available for " + priority);
  }

  private static SubmitResult submitOn(SmppBind bind, SMPPSession session, SmsPart part) throws SmsSendException {
    boolean alphanumeric = !part.source().chars().allMatch(Character::isDigit);
    ESMClass esm = new ESMClass(MessageMode.DEFAULT, MessageType.DEFAULT,
        part.udhi() ? GSMSpecificFeature.UDHI : GSMSpecificFeature.DEFAULT);
    try {
      String id = session.submitShortMessage("",
              alphanumeric ? TypeOfNumber.ALPHANUMERIC : TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN,
              part.source(), TypeOfNumber.UNKNOWN, NumberingPlanIndicator.ISDN, part.destination(), esm,
              (byte) 0, (byte) 0, null, null, new RegisteredDelivery(SMSCDeliveryReceipt.SUCCESS_FAILURE),
              (byte) 0, DataCodings.newInstance(part.dataCoding()), (byte) 0, part.shortMessage())
          .getMessageId();
      return new SubmitResult(bind.name(), id);
    } catch (NegativeResponseException e) {
      int status = e.getCommandStatus();
      throw new SmsSendException(TRANSIENT_STATUSES.contains(status), status, bind.name(),
          "submit_sm refused, status 0x" + Integer.toHexString(status));
    } catch (ResponseTimeoutException | InvalidResponseException e) {
      throw new SmsSendException(true, -1, bind.name(), "submit_sm without a valid response");
    } catch (IOException e) {
      bind.reset();
      throw new SmsSendException(true, -1, bind.name(), "SMPP connection error");
    } catch (PDUException e) {
      throw new SmsSendException(false, -1, bind.name(), "submit_sm could not be built");
    }
  }

  /** Whether every priority has at least one bound session. */
  public boolean allPoolsBound() {
    return pools.values().stream().allMatch(binds -> binds.stream().anyMatch(SmppBind::isBound));
  }

  /** Brings binds up (respecting their back-off) so DLRs can arrive while nothing is sent. */
  @Override
  public void maintain() {
    pools.values().forEach(binds -> binds.forEach(SmppBind::bound));
  }

  @PreDestroy
  public void close() {
    pools.values().forEach(binds -> binds.forEach(SmppBind::close));
  }

  /** DLRs from the SMSC (deliver_sm with the SMSC delivery receipt flag). */
  private static final class Receiver implements MessageReceiverListener {

    private final DeliveryReportHandler handler;

    Receiver(DeliveryReportHandler handler) {
      this.handler = handler;
    }

    @Override
    public void onAcceptDeliverSm(DeliverSm deliverSm) throws ProcessRequestException {
      if (!MessageType.SMSC_DEL_RECEIPT.containedIn(deliverSm.getEsmClass())) {
        log.atInfo().addKeyValue("event", "smpp.deliver_sm.ignored").log("mobile-originated SMS ignored");
        return;
      }
      DeliveryReceipt receipt;
      try {
        receipt = deliverSm.getShortMessageAsDeliveryReceipt();
      } catch (InvalidDeliveryReceiptException e) {
        log.atWarn().addKeyValue("event", "smpp.dlr.invalid").log("unreadable delivery receipt");
        return;
      }
      DlrOutcome outcome = outcome(receipt.getFinalStatus());
      if (outcome == null) {
        return;
      }
      try {
        handler.onReport(new DeliveryReport(receipt.getId(), outcome));
      } catch (RuntimeException e) {
        log.atError().addKeyValue("event", "smpp.dlr.failed").addKeyValue("errorClass", e.getClass().getName())
            .log("DLR not stored; the SMSC will redeliver it");
        throw new ProcessRequestException("DLR not stored", SMPPConstant.STAT_ESME_RSYSERR);
      }
    }

    @Override
    public void onAcceptAlertNotification(AlertNotification alertNotification) {
      // not used
    }

    @Override
    public DataSmResult onAcceptDataSm(DataSm dataSm, Session source) throws ProcessRequestException {
      throw new ProcessRequestException("data_sm not supported", SMPPConstant.STAT_ESME_RINVCMDID);
    }
  }

  /** Final DLR states (spec 10.2); intermediate ones (ENROUTE, ACCEPTD, UNKNOWN) give null. */
  static DlrOutcome outcome(DeliveryReceiptState state) {
    if (state == null) {
      return null;
    }
    return switch (state) {
      case DELIVRD -> DlrOutcome.DELIVERED;
      case EXPIRED -> DlrOutcome.EXPIRED;
      case UNDELIV, REJECTD, DELETED -> DlrOutcome.UNDELIVERABLE;
      default -> null;
    };
  }
}

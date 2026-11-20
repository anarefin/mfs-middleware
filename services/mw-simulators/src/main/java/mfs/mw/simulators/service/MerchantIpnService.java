package mfs.mw.simulators.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.simulators.config.SimulatorProperties;
import mfs.mw.simulators.dto.ReceivedIpn;
import mfs.mw.simulators.exception.MockAbortException;
import mfs.mw.simulators.service.mockcontrol.Decision;
import mfs.mw.simulators.service.mockcontrol.MockCalls;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import mfs.mw.simulators.service.mockcontrol.PartyState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Merchant IPN receiver (ticket 32; mock party {@code merchant}, operation {@code ipn}, keys {@code ipnId}
 * and {@code path}): plays a checkout merchant's server that mw-checkout posts its signed IPN to. Answers
 * 200 unless mock control says otherwise (HTTP_5XX its {@code httpStatus}, default 500; BUSINESS_ERROR
 * its {@code httpStatus}, default 400; TIMEOUT holds the 200; CONNECTION_RESET and DOWN close the
 * connection; MAINTENANCE 503); records every IPN (payment and refund IPNs, told apart by the body's
 * {@code type}, ticket 116) with the status it answered ({@code -1} = no answer;
 * last {@code sim.record-limit}). State is in memory.
 */
@Service
public class MerchantIpnService implements PartyState {

  public static final String PARTY = "merchant";

  private final MockControl control;
  private final Clock clock;
  private final int recordLimit;
  private final List<ReceivedIpn> received = new ArrayList<>();
  private long seq;

  @Autowired
  public MerchantIpnService(MockControl control, Clock clock, SimulatorProperties props) {
    this(control, clock, props.recordLimit());
  }

  public MerchantIpnService(MockControl control, Clock clock, int recordLimit) {
    this.control = control;
    this.clock = clock;
    this.recordLimit = recordLimit;
  }

  /** Handles one IPN: the status to answer. */
  public int receive(String path, Map<String, String> headers, String body) {
    String ipnId = headers.get("x-bits-ipn-id");
    Map<String, String> keys = new HashMap<>();
    keys.put("path", path);
    if (ipnId != null) {
      keys.put("ipnId", ipnId);
    }
    Decision d = control.decide(PARTY, "ipn", keys, mfs.mw.simulators.dto.SimMode.SUCCESS);
    MockCalls.pause(d.delayMs());
    int status = switch (d.gate()) {
      case DOWN -> -1;
      case MAINTENANCE -> 503;
      case OPEN -> switch (d.mode()) {
        case BUSINESS_FAILURE -> d.statusOr(400);
        case SERVER_ERROR_AFTER_SEND -> d.statusOr(500);
        case CONNECTION_RESET -> -1;
        default -> 200;
      };
    };
    record(path, ipnId, headers, body, status);
    if (status == -1) {
      throw new MockAbortException(PARTY);
    }
    if (d.gate() == Decision.Gate.OPEN && d.mode() == mfs.mw.simulators.dto.SimMode.TIMEOUT) {
      MockCalls.pause(d.holdMs());
    }
    return status;
  }

  private synchronized void record(String path, String ipnId, Map<String, String> headers, String body, int status) {
    received.add(new ReceivedIpn(++seq, clock.instant(), path, ipnId, type(body), Map.copyOf(headers), body, status));
    while (received.size() > recordLimit) {
      received.removeFirst();
    }
  }

  /** What was received, oldest first; only IPNs with this {@code X-Bits-Ipn-Id} when given. */
  public synchronized List<ReceivedIpn> received(String ipnId) {
    return received(ipnId, null);
  }

  /** As {@link #received(String)}, also only IPNs of this type ({@code PAYMENT} or {@code REFUND}) when given. */
  public synchronized List<ReceivedIpn> received(String ipnId, String type) {
    return received.stream()
        .filter(r -> ipnId == null || ipnId.equals(r.ipnId()))
        .filter(r -> type == null || type.equalsIgnoreCase(r.type()))
        .toList();
  }

  /** A refund IPN says {@code "type":"REFUND"} in its body (mw-checkout's RefundIpn, ticket 116). */
  static String type(String body) {
    return body != null && REFUND_TYPE.matcher(body).find() ? ReceivedIpn.REFUND : ReceivedIpn.PAYMENT;
  }

  private static final java.util.regex.Pattern REFUND_TYPE =
      java.util.regex.Pattern.compile("\"type\"\\s*:\\s*\"REFUND\"");

  /** Forgets the received IPNs. */
  public synchronized void reset() {
    received.clear();
  }

  @Override
  public void reset(String party) {
    if (PARTY.equals(party)) {
      reset();
    }
  }
}

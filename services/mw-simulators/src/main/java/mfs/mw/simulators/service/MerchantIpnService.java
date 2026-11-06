package mfs.mw.simulators.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mfs.mw.simulators.config.SimulatorProperties;
import mfs.mw.simulators.dto.MerchantIpnScenario;
import mfs.mw.simulators.dto.ReceivedIpn;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Merchant IPN receiver (ticket 32): plays a checkout merchant's server that mw-checkout posts its
 * signed IPN to. Answers 200 unless the scenario says to fail the next N; records every IPN with
 * the status it answered (last {@code sim.record-limit}). State is in memory.
 */
@Service
public class MerchantIpnService {

  private final Clock clock;
  private final int recordLimit;
  private final List<ReceivedIpn> received = new ArrayList<>();
  private MerchantIpnScenario scenario = MerchantIpnScenario.ACCEPT;
  private long seq;

  @Autowired
  public MerchantIpnService(Clock clock, SimulatorProperties props) {
    this(clock, props.recordLimit());
  }

  public MerchantIpnService(Clock clock, int recordLimit) {
    this.clock = clock;
    this.recordLimit = recordLimit;
  }

  /** Handles one IPN: the status to answer. */
  public synchronized int receive(String path, Map<String, String> headers, String body) {
    int status = 200;
    String ipnId = headers.get("x-bits-ipn-id");
    if (scenario.appliesTo(ipnId)) {
      status = scenario.failStatusOrDefault();
      scenario = new MerchantIpnScenario(scenario.failNext() - 1, scenario.failStatus(), scenario.ipnId());
    }
    received.add(new ReceivedIpn(++seq, clock.instant(), path, ipnId, Map.copyOf(headers), body, status));
    while (received.size() > recordLimit) {
      received.removeFirst();
    }
    return status;
  }

  public synchronized void setScenario(MerchantIpnScenario s) {
    scenario = s;
  }

  public synchronized MerchantIpnScenario scenario() {
    return scenario;
  }

  /** What was received, oldest first; only IPNs with this {@code X-Bits-Ipn-Id} when given. */
  public synchronized List<ReceivedIpn> received(String ipnId) {
    return received.stream().filter(r -> ipnId == null || ipnId.equals(r.ipnId())).toList();
  }

  public synchronized void reset() {
    received.clear();
    scenario = MerchantIpnScenario.ACCEPT;
  }
}

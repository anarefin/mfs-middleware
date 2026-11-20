package mfs.mw.simulators.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.SplittableRandom;
import java.util.Map;
import mfs.mw.simulators.config.SimulatorProperties;
import mfs.mw.simulators.dto.MockBehaviour;
import mfs.mw.simulators.dto.MockScenario;
import mfs.mw.simulators.dto.MockSwitch;
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.service.mockcontrol.PartySwitch;
import mfs.mw.simulators.service.mockcontrol.ScenarioEngine;
import org.junit.jupiter.api.Test;

/**
 * A partner reverse (ticket 97, FRS MCK-02): the simulated OCS, aggregator and C2W bank reverse a
 * successful transaction once; a second reverse answers the first result again, an unknown
 * transaction is NOT_FOUND, a failed one cannot be reversed, an outage is 503 with nothing applied.
 */
class ReversalServiceTest {

  private final SimulatorProperties props = new SimulatorProperties(Duration.ofMillis(30), 100);
  private final PartySwitch switches = new PartySwitch();
  private final ScenarioEngine engine = new ScenarioEngine(switches, props, new SplittableRandom(1));
  private final OcsLedger ocs = new OcsLedger();
  private final BillPaymentLedger billagg = new BillPaymentLedger();
  private final BankDebitLedger bank = new BankDebitLedger();
  private final ReversalService service = new ReversalService(engine, ocs, billagg, bank, new mfs.mw.simulators.service.AggregatorBillLedger());

  @SuppressWarnings("unchecked")
  private static Map<String, Object> body(SimReply reply) {
    return (Map<String, Object>) reply.body();
  }

  @Test
  void aSuccessfulTopupIsReversedOnceAndASecondReverseAnswersTheFirstResult() {
    ocs.put(new TopupRecord("BITS01", "01711000001", "20.00", "SUCCESS", "0000", "ok", "OCS-1", SimMode.SUCCESS));

    SimReply first = service.reverse("ocs", "BITS01", "reverse:txn-1");
    SimReply second = service.reverse("ocs", "BITS01", "reverse:txn-1-again");

    assertThat(first.httpStatus()).isEqualTo(200);
    assertThat(body(first)).containsEntry("status", "REVERSED").containsEntry("transactionId", "BITS01")
        .containsEntry("reversalId", "reverse:txn-1").containsEntry("code", "0000");
    assertThat((String) body(first).get("reversalRef")).startsWith("REV-");
    assertThat(second.httpStatus()).isEqualTo(200);
    assertThat(body(second)).isEqualTo(body(first));
    assertThat(service.reversals()).isEqualTo(1);
  }

  @Test
  void billPaymentsAndBankDebitsAreReversedToo() {
    billagg.putIfAbsent(new BillPaymentRecord("BITS02", "DESCO", "77001234", "20.00", "SUCCESS", "0000", "ok", "AGG-1",
        SimMode.SUCCESS, false));
    bank.putIfAbsent(new BankDebitRecord("BITS03", "1234567890", "50.00", "SUCCESS", "0000", "ok", "C2W-1", SimMode.SUCCESS));

    assertThat(body(service.reverse("billagg", "BITS02", "reverse:a"))).containsEntry("status", "REVERSED");
    assertThat(body(service.reverse("c2wbank", "BITS03", "reverse:b"))).containsEntry("status", "REVERSED");
  }

  @Test
  void anUnknownTransactionIsNotFoundAndAFailedOneCannotBeReversed() {
    ocs.put(new TopupRecord("BITS04", "01711000001", "20.00", "FAILED", "2001", "refused", null, SimMode.BUSINESS_FAILURE));

    SimReply unknown = service.reverse("ocs", "BITS-NONE", "reverse:c");
    SimReply failed = service.reverse("ocs", "BITS04", "reverse:d");

    assertThat(unknown.httpStatus()).isEqualTo(404);
    assertThat(body(unknown)).containsEntry("status", "NOT_FOUND");
    assertThat(failed.httpStatus()).isEqualTo(422);
    assertThat(body(failed)).containsEntry("status", "FAILED").containsEntry("code", "4091");
    assertThat(service.reversals()).isZero();
  }

  @Test
  void aBusinessErrorScenarioRefusesWithItsCodeAndAResetPartyForgetsItsReversals() {
    ocs.put(new TopupRecord("BITS06", "01711000001", "20.00", "SUCCESS", "0000", "ok", "OCS-6", SimMode.SUCCESS));
    MockScenario s = engine.add("ocs", MockScenario.of("reverse", MockBehaviour.BUSINESS_ERROR).withCode("4099").withNextN(1));

    SimReply refused = service.reverse("ocs", "BITS06", "reverse:f");
    assertThat(refused.httpStatus()).isEqualTo(422);
    assertThat(body(refused)).containsEntry("code", "4099");
    assertThat(engine.list("ocs")).extracting(MockScenario::id).doesNotContain(s.id());
    assertThat(service.reverse("ocs", "BITS06", "reverse:f").httpStatus()).isEqualTo(200);
    assertThat(service.reversals()).isEqualTo(1);
    service.reset("billagg");
    assertThat(service.reversals()).isEqualTo(1);
    service.reset("ocs");
    assertThat(service.reversals()).isZero();
  }

  @Test
  void anOutageAnswers503AndAppliesNothing() {
    ocs.put(new TopupRecord("BITS05", "01711000001", "20.00", "SUCCESS", "0000", "ok", "OCS-5", SimMode.SUCCESS));
    switches.set("ocs", new MockSwitch(MockSwitch.State.MAINTENANCE, null, null, null));

    assertThat(service.reverse("ocs", "BITS05", "reverse:e").httpStatus()).isEqualTo(503);
    assertThat(service.reversals()).isZero();
  }
}

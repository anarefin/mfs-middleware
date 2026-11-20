package mfs.mw.simulators.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import mfs.mw.simulators.dto.MockScenario;
import mfs.mw.simulators.dto.MockSettings;
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.service.mockcontrol.Decision;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Four operator OCS profiles (ticket 112a, FRS EXT-08): each speaks its own JSON and error code, and
 * a seeded postpaid account has an outstanding bill and a minimum. A reversed recharge answers the
 * first result again (MCK-02).
 */
class OperatorOcsServiceTest {

  private final OperatorOcsService service = new OperatorOcsService(magicOnly());

  @ParameterizedTest
  @EnumSource(OperatorOcsProfile.class)
  void aPrepaidRechargeUsesThatOperatorsShape(OperatorOcsProfile profile) {
    SimReply reply = service.recharge(profile, recharge(profile, "01719990001", "20.00"));

    assertThat(reply.httpStatus()).isEqualTo(200);
    assertThat(body(reply)).containsAllEntriesOf(profile.successShape(profile.successCode()));
  }

  @ParameterizedTest
  @EnumSource(OperatorOcsProfile.class)
  void aBarredMsisdnUsesThatOperatorsError(OperatorOcsProfile profile) {
    SimReply reply = service.recharge(profile, recharge(profile, "01719990999", "20.00"));

    assertThat(reply.httpStatus()).isEqualTo(profile.rejectStatus());
    assertThat(body(reply)).containsAllEntriesOf(profile.failureShape(profile.barredCode(), profile.barredMessage()));
  }

  @ParameterizedTest
  @EnumSource(OperatorOcsProfile.class)
  void aSeededPostpaidAccountReturnsTheOutstandingBillAndTheMinimum(OperatorOcsProfile profile) {
    SimReply reply = service.bill(profile, Map.of(profile.msisdnField(), profile.seedMsisdn()));

    assertThat(reply.httpStatus()).isEqualTo(200);
    assertThat(body(reply))
        .containsAllEntriesOf(profile.billShape(profile.seedOutstanding(), profile.seedMinimum(), profile.seedBillRef()));
  }

  @ParameterizedTest
  @EnumSource(OperatorOcsProfile.class)
  void anUnknownPostpaidNumberIsThatOperatorsUnknownBill(OperatorOcsProfile profile) {
    SimReply reply = service.bill(profile, Map.of(profile.msisdnField(), "01710000000"));

    assertThat(reply.httpStatus()).isEqualTo(profile.rejectStatus());
    assertThat(body(reply)).containsEntry(profile.codeField(), profile.unknownBillCode());
  }

  @ParameterizedTest
  @EnumSource(OperatorOcsProfile.class)
  void aSecondReverseReturnsTheFirstOutcome(OperatorOcsProfile profile) {
    Map<String, Object> request = recharge(profile, profile.seedMsisdn(), "20.00");
    service.recharge(profile, request);
    String txn = (String) request.get(profile.txnField());

    SimReply first = service.reverse(profile, txn, Map.of(profile.reverseField(), "reverse:txn-1"));
    SimReply second = service.reverse(profile, txn, Map.of(profile.reverseField(), "reverse:txn-1-again"));

    assertThat(first.httpStatus()).isEqualTo(200);
    assertThat(second.httpStatus()).isEqualTo(200);
    assertThat(body(second)).containsEntry(profile.refField(), body(first).get(profile.refField()));
    assertThat(body(first).get(profile.refField())).isNotNull();
  }

  @Test
  void teletalkIssuesAClientCredentialsToken() {
    SimReply token = service.token();

    assertThat(token.httpStatus()).isEqualTo(200);
    assertThat(body(token)).containsEntry("access_token", "dev-teletalk-token").containsEntry("token_type", "bearer");
  }

  @Test
  void statusOfAnUnknownRechargeIsNotFound() {
    SimReply reply = service.status(OperatorOcsProfile.GP, "BITS-NONE");

    assertThat(reply.httpStatus()).isEqualTo(404);
    assertThat(body(reply)).containsEntry("status", "NOT_FOUND");
  }

  private static Map<String, Object> recharge(OperatorOcsProfile profile, String msisdn, String amount) {
    return Map.of(
        profile.txnField(), "BITS-" + profile.name(),
        profile.msisdnField(), msisdn,
        profile.amountField(), amount);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> body(SimReply reply) {
    return (Map<String, Object>) reply.body();
  }

  /** No scenarios and no pause: the call's own magic value is the outcome. */
  private static MockControl magicOnly() {
    return new MockControl() {
      @Override
      public Decision decide(String party, String operation, Map<String, String> keys, SimMode magic) {
        return Decision.magic(magic, 0);
      }

      @Override
      public MockSettings settings(String party) {
        return MockSettings.DEFAULT;
      }

      @Override
      public Optional<MockScenario> fileFault(String party, String operation, Map<String, String> keys) {
        return Optional.empty();
      }
    };
  }
}

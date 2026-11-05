package mfs.mw.perf.ussd;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class UssdSessionsTest {

  @Test
  void a_ussd_p2p_dials_robi_in_english_and_sends_ten_taka_with_the_seeded_pin() {
    JsonNode b = JsonMapper.builder().build().readTree(UssdSessions.p2p("01711000301", "01711000102", "1234"));

    assertThat(b.path("mno").asString()).isEqualTo("robi");
    assertThat(b.path("msisdn").asString()).isEqualTo("01711000301");
    assertThat(b.path("serviceCode").asString()).isEqualTo("*268#");
    // tests/e2e ussd.feature: 9 English, 1 Send Money, recipient, amount in taka, 1 confirm, PIN
    assertThat(b.path("inputs").valueStream().map(JsonNode::asString))
        .containsExactly("9", "1", "01711000102", "10", "1", "1234");
  }

  @Test
  void the_last_screen_tells_whether_the_money_was_sent_and_carries_the_txn_id() {
    String done = "Tk 10.00 sent to 01711000102. TxnID 0b6f1e5e-0000-4000-8000-000000000001";

    assertThat(UssdSessions.sent(done)).isTrue();
    assertThat(UssdSessions.sent("Insufficient balance")).isFalse();
    assertThat(UssdSessions.sent(null)).isFalse();
    assertThat(UssdSessions.txnId(done)).isEqualTo("0b6f1e5e-0000-4000-8000-000000000001");
    assertThat(UssdSessions.txnId("Insufficient balance")).isNull();
  }
}

package mfs.mw.ussd;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import mfs.mw.ussd.messaging.OrchestratorStub;
import mfs.mw.ussd.util.TestPinKeys;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.junit.jupiter.api.BeforeEach;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * P2P over the HTTP transport end to end inside the module (spec 9.2, ticket 26): real
 * PostgreSQL (menus), Valkey (sessions) and Kafka, with a stub playing mw-orchestrator. The menu
 * comes from the seeder; every step is a POST to {@code /ussd/robi} as an MNO sends it.
 */
@SpringBootTest(properties = {"mw.ussd.sync-budget=5s", "mw.ussd.instance-id=it-ussd-1", "mw.ussd.consumers.enabled=true"})
@AutoConfigureMockMvc
@Import(TestInfrastructure.class)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UssdHttpFlowIT {

  static final String CUSTOMER = "8801712345678";
  static final String PAYEE = "01812345678";
  static final String PIN = "48261";
  static final JsonMapper JSON = JsonMapper.builder().build();

  private final MockMvcTester http;
  private final StringRedisTemplate valkey;
  /** Every session value seen while a step was in flight or after it. */
  private final List<String> seenSessions = new CopyOnWriteArrayList<>();
  private OrchestratorStub orchestrator;

  @Autowired
  UssdHttpFlowIT(MockMvcTester http, StringRedisTemplate valkey) {
    this.http = http;
    this.valkey = valkey;
  }

  @BeforeAll
  void startStub() {
    TestTopics.ensure("mw.txn.request.v1", "mw.txn.status.v1");
    // the stub looks inside Valkey at the moment the request (with the PIN ciphertext) arrives
    orchestrator = new OrchestratorStub(TestTopics.bootstrap(), r -> snapshotSessions());
  }

  @BeforeEach
  void replyConsumerReady() {
    TestTopics.awaitGroupStable("mw-ussd.reply.it-ussd-1", Duration.ofSeconds(60));
  }

  @AfterAll
  void stopStub() {
    orchestrator.close();
  }

  private void snapshotSessions() {
    Set<String> keys = valkey.keys("ussd:*");
    for (String k : keys) {
      String v = valkey.opsForValue().get(k);
      if (v != null) {
        seenSessions.add(v);
      }
    }
  }

  JsonNode step(String session, String type, String input) {
    try {
      return doStep(session, type, input);
    } catch (java.io.UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }

  JsonNode doStep(String session, String type, String input) throws java.io.UnsupportedEncodingException {
    ObjectNode body = JSON.createObjectNode();
    body.put("sessionId", session).put("msisdn", CUSTOMER).put("serviceCode", "*269#").put("type", type);
    if (input != null) {
      body.put("input", input);
    }
    var result =
        http.post().uri("/ussd/robi").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body))
            .exchange();
    assertThat(result).hasStatusOk();
    String out = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    snapshotSessions();
    return JSON.readTree(out);
  }

  /** Dials and walks to the PIN prompt; returns the screens. */
  List<String> walkToPin(String session, boolean english, String amount) {
    List<String> screens = new ArrayList<>();
    screens.add(step(session, "BEGIN", "").path("message").asString());
    if (english) {
      screens.add(step(session, "CONTINUE", "9").path("message").asString());
    }
    screens.add(step(session, "CONTINUE", "1").path("message").asString());
    screens.add(step(session, "CONTINUE", PAYEE).path("message").asString());
    screens.add(step(session, "CONTINUE", amount).path("message").asString());
    screens.add(step(session, "CONTINUE", "1").path("message").asString());
    return screens;
  }

  @Test
  void p2pFromDialToConfirmationInEnglish() throws Exception {
    String session = "it-" + UUID.randomUUID();
    List<String> screens = walkToPin(session, true, "10");
    assertThat(screens)
        .containsExactly(
            "Bits\n1 সেন্ড মানি\n2 ক্যাশ আউট\n3 রিচার্জ\n4 বিল পে\n5 ব্যালেন্স\n9 English",
            "Bits\n1 Send Money\n2 Cash Out\n3 Recharge\n4 Pay Bill\n5 Balance\n9 বাংলা",
            "Enter recipient Bits number",
            "Enter amount (Tk)",
            "Send Tk 10.00 to 01812345678?\n1 Confirm\n2 Cancel",
            "Enter your PIN");

    JsonNode done = step(session, "CONTINUE", PIN);

    assertThat(done.path("flag").asString()).isEqualTo("END");
    assertThat(done.path("message").asString()).startsWith("Tk 10.00 sent to 01812345678. TxnID ");
    ConsumerRecord<String, String> rec = orchestrator.requests().getLast();
    JsonNode req = JSON.readTree(rec.value());
    assertThat(req.path("channel").asString()).isEqualTo("USSD");
    assertThat(req.path("initiator").path("id").asString()).isEqualTo("01712345678");
    assertThat(req.path("creditParty").path("id").asString()).isEqualTo(PAYEE);
    assertThat(req.path("amount").path("valueMinor").asLong()).isEqualTo(OrchestratorStub.COMPLETES);
    assertThat(req.path("idempotencyKey").asString()).matches("[0-9a-f]{64}");
    // the request carries mw-auth's assertion, never the PIN (ticket 72)
    assertThat(req.has("encryptedPin")).isFalse();
    assertThat(req.path("assertion").asString()).isEqualTo(AuthStub.ASSERTION);
    // mw-auth was asked with the PIN under the transport key and exactly the published values
    JsonNode asked = AuthStub.authorisations().getLast();
    assertThat(asked.path("msisdn").asString()).isEqualTo("01712345678");
    assertThat(asked.path("channel").asString()).isEqualTo("USSD");
    assertThat(asked.path("encryptedPin").path("keyId").asString()).isEqualTo(AuthStub.KEY_ID);
    String ciphertext = asked.path("encryptedPin").path("ciphertext").asString();
    assertThat(TestPinKeys.decrypt(new mfs.mw.ussd.dto.EncryptedPin(AuthStub.KEY_ID, ciphertext))).isEqualTo(PIN);
    assertThat(asked.path("txn").path("idempotencyKey").asString()).isEqualTo(req.path("idempotencyKey").asString());
    assertThat(asked.path("txn").path("amount").asLong()).isEqualTo(req.path("amount").path("valueMinor").asLong());
    assertThat(asked.path("txn").path("payer").path("id").asString()).isEqualTo(req.path("debitParty").path("id").asString());
    assertThat(asked.path("txn").path("payee").path("id").asString()).isEqualTo(req.path("creditParty").path("id").asString());
    assertThat(done.path("message").asString()).endsWith(req.path("txnId").asString());
    assertThat(valkey.hasKey("ussd:robi:" + session)).isFalse();
  }

  @Test
  void p2pFromDialToConfirmationInBangla() {
    String session = "it-" + UUID.randomUUID();
    List<String> screens = walkToPin(session, false, "10");
    assertThat(screens.getLast()).isEqualTo("আপনার পিন দিন");

    JsonNode done = step(session, "CONTINUE", PIN);

    assertThat(done.path("flag").asString()).isEqualTo("END");
    assertThat(done.path("message").asString()).isEqualTo("01812345678 নম্বরে 10.00 টাকা পাঠানো হয়েছে।");
  }

  @Test
  void theValkeySessionNeverHoldsThePin() {
    String session = "it-" + UUID.randomUUID();
    walkToPin(session, true, "10");
    String atPinPrompt = valkey.opsForValue().get("ussd:robi:" + session);
    assertThat(atPinPrompt).contains("\"nodeId\":\"p2p.pin\"");
    assertThat(valkey.getExpire("ussd:robi:" + session)).isBetween(140L, 150L);

    step(session, "CONTINUE", PIN);

    String ciphertext = AuthStub.authorisations().getLast().path("encryptedPin").path("ciphertext").asString();
    assertThat(seenSessions).isNotEmpty();
    assertThat(seenSessions).allSatisfy(v -> assertThat(v).doesNotContain(PIN).doesNotContain(ciphertext));
  }

  @Test
  void aWrongPinIsAskedAgainAndALockEndsTheSessionWithNothingPublished() {
    String session = "it-" + UUID.randomUUID();
    walkToPin(session, true, "10");
    int published = orchestrator.requests().size();
    try {
      AuthStub.refuse(401, "MW-AUTH-040", "triesLeft", "2");
      JsonNode again = step(session, "CONTINUE", "11111");
      assertThat(again.path("flag").asString()).isEqualTo("CON");
      assertThat(again.path("message").asString()).isEqualTo("Wrong PIN. 2 tries left.\nEnter your PIN");

      AuthStub.refuse(423, "MW-AUTH-041", "retryAfter", "60");
      JsonNode locked = step(session, "CONTINUE", "22222");
      assertThat(locked.path("flag").asString()).isEqualTo("END");
      assertThat(locked.path("message").asString()).isEqualTo("Your PIN is locked for now. Please try again later.");
    } finally {
      AuthStub.defaults();
    }
    assertThat(orchestrator.requests()).hasSize(published);
    assertThat(valkey.hasKey("ussd:robi:" + session)).isFalse();
  }

  @Test
  void validatorsRejectABadNumberOrAmountAndPromptAgain() {
    String session = "it-" + UUID.randomUUID();
    step(session, "BEGIN", "");
    step(session, "CONTINUE", "9");
    step(session, "CONTINUE", "1");
    assertThat(step(session, "CONTINUE", "0171").path("message").asString())
        .isEqualTo("Enter a valid mobile number.\nEnter recipient Bits number");
    step(session, "CONTINUE", PAYEE);
    JsonNode bad = step(session, "CONTINUE", "99999");
    assertThat(bad.path("message").asString()).isEqualTo("Enter an amount from Tk 10 to 25000.\nEnter amount (Tk)");
    assertThat(bad.path("flag").asString()).isEqualTo("CON");
  }

  @Tag("slow-wait")
  @Test
  void noFinalAnswerWithinTheBudgetEndsWithBeingProcessed() {
    String session = "it-" + UUID.randomUUID();
    // 20.00 Tk = 2000 poisha: the stub never answers beyond RECEIVED
    walkToPin(session, true, "20");

    JsonNode done = step(session, "CONTINUE", PIN);

    assertThat(done.path("flag").asString()).isEqualTo("END");
    assertThat(done.path("message").asString())
        .isEqualTo("Your request is being processed. You will get the result by SMS.");
  }

  @Test
  void aCoreRefusalEndsWithItsMessage() {
    String session = "it-" + UUID.randomUUID();
    // 30.00 Tk = 3000 poisha: the stub rejects with INSUFFICIENT_BALANCE
    walkToPin(session, false, "30");

    assertThat(step(session, "CONTINUE", PIN).path("message").asString())
        .isEqualTo("সেন্ড মানি ব্যর্থ: অপর্যাপ্ত ব্যালেন্স");
  }

  /**
   * ADR D26 over Kafka end to end: 5 -> PIN -> the balance the orchestrator's COMPLETED carries.
   * mw-auth authorised exactly the published BALANCE request (amount 0, payee = payer), and two
   * checks in a row are two inquiries (fresh idempotency keys), never a replay.
   */
  @Test
  void balanceFromDialToTheBalanceScreen() {
    List<String> keys = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      String session = "it-" + UUID.randomUUID();
      assertThat(step(session, "BEGIN", "").path("message").asString()).contains("5 ব্যালেন্স");
      step(session, "CONTINUE", "9");
      assertThat(step(session, "CONTINUE", "5").path("message").asString()).isEqualTo("Enter your PIN");

      JsonNode done = step(session, "CONTINUE", PIN);

      assertThat(done.path("flag").asString()).isEqualTo("END");
      assertThat(done.path("message").asString()).isEqualTo("Your balance is Tk 12,000.00");
      JsonNode req = JSON.readTree(orchestrator.requests().getLast().value());
      assertThat(req.path("txnType").asString()).isEqualTo("BALANCE");
      assertThat(req.path("amount").path("valueMinor").asLong()).isZero();
      assertThat(req.path("creditParty")).isEqualTo(req.path("debitParty"));
      assertThat(req.path("debitParty").path("id").asString()).isEqualTo("01712345678");
      assertThat(req.path("assertion").asString()).isEqualTo(AuthStub.ASSERTION);
      JsonNode asked = AuthStub.authorisations().getLast();
      assertThat(asked.path("txn").path("txnType").asString()).isEqualTo("BALANCE");
      assertThat(asked.path("txn").path("amount").asLong()).isZero();
      assertThat(asked.path("txn").path("payee")).isEqualTo(asked.path("txn").path("payer"));
      assertThat(asked.path("txn").path("idempotencyKey").asString()).isEqualTo(req.path("idempotencyKey").asString());
      keys.add(req.path("idempotencyKey").asString());
      assertThat(valkey.hasKey("ussd:robi:" + session)).isFalse();
    }
    assertThat(keys).doesNotHaveDuplicates();
  }
}

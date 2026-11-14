package mfs.mw.app.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import mfs.mw.app.gateway.TestInfrastructure;
import mfs.mw.app.gateway.ValkeyInfrastructure;
import mfs.mw.app.gateway.client.AuthClient;
import mfs.mw.app.gateway.dto.BoundDevice;
import mfs.mw.app.gateway.dto.LiveSession;
import mfs.mw.app.gateway.dto.SubmitResult;
import mfs.mw.app.gateway.dto.TransactionView;
import mfs.mw.app.gateway.service.DeviceVerifier;
import mfs.mw.app.gateway.service.RequestSignatures;
import mfs.mw.app.gateway.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The device gate as a verifier (spec 9.1, 18.6; ticket 29, slimmed in ticket 68) against real
 * Valkey (device key cache, session confirmations, nonces): signed submits, replays, tampered or
 * re-encoded bodies, unbound devices, devices of another wallet and missing sessions. mw-auth (the
 * owner of bindings and sessions, {@link AuthClient}) is a stub holding the bound devices and live
 * sessions of each test; the binding itself is tested in mw-auth. The transaction service is a stub
 * too: what is under test is the gate in front of it.
 */
@SpringBootTest(
    properties = {
      "mw.app-gateway.device-binding.enabled=true",
      "mw.app-gateway.consumers.enabled=false",
      "mw.outbox.relay.enabled=false",
      // the shared Testcontainers Valkey; the shipped 500 ms is for production
      "spring.data.redis.timeout=3s"
    })
@AutoConfigureMockMvc
@Import({TestInfrastructure.class, ValkeyInfrastructure.class})
class DeviceBindingIT {

  static final String TXN_ID = "019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0d";
  static final String P2P =
      """
      {"txnType":"P2P","creditParty":{"id":"01711000002"},
       "amount":{"valueMinor":1000,"currency":"BDT"},
       "encryptedPin":{"keyId":"core-pin-1","ciphertext":"Y2lwaGVy"}}""";

  private final MockMvcTester mvc;

  @MockitoBean TransactionService transactions;
  @MockitoBean AuthClient auth;

  /** mw-auth's state as the stub answers it: bound devices by id, live sessions as wallet:device. */
  private final Map<String, BoundDevice> bound = new ConcurrentHashMap<>();
  private final Set<String> sessions = ConcurrentHashMap.newKeySet();

  private String msisdn;

  @Autowired
  DeviceBindingIT(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  /** A wallet of its own per test, so tests never see each other's cached keys or sessions. */
  @BeforeEach
  void freshWallet() {
    msisdn = "0171" + String.format("%07d", (int) (Math.random() * 10_000_000));
    when(transactions.submit(any(), any(), any()))
        .thenReturn(SubmitResult.processing(TransactionView.processing(TXN_ID)));
    when(transactions.find(any(), any())).thenReturn(TransactionView.processing(TXN_ID));
    when(auth.device(anyString())).thenAnswer(i -> Optional.ofNullable(bound.get(i.<String>getArgument(0))));
    when(auth.session(anyString(), anyString())).thenAnswer(i -> {
      String w = i.getArgument(0);
      BoundDevice d = bound.get(i.<String>getArgument(1));
      return d != null && d.walletId().equals(w) && sessions.contains(w + ":" + d.deviceId())
          ? Optional.of(new LiveSession(w, d.deviceId(), 300, DeviceVerifier.fingerprint(d.publicKey()), d.boundAt()))
          : Optional.empty();
    });
  }

  record Device(String id, KeyPair key) {
    static Device create() throws Exception {
      KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
      g.initialize(new ECGenParameterSpec("secp256r1"));
      return new Device("dev-" + UUID.randomUUID(), g.generateKeyPair());
    }

    String publicKey() {
      return Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
    }
  }

  /** What a binding at mw-auth leaves behind: the device bound to the wallet and a live session. */
  Device bind() throws Exception {
    Device d = Device.create();
    bound.put(d.id(), new BoundDevice(d.id(), msisdn, d.publicKey(), Instant.now()));
    sessions.add(msisdn + ":" + d.id());
    return d;
  }

  /** The signature headers an app sends, signed with {@code device}'s key. */
  record Signed(String deviceId, String timestamp, String nonce, String signature) {}

  static Signed sign(Device device, String method, String path, String body) throws Exception {
    String ts = Long.toString(Instant.now().toEpochMilli());
    String nonce = "n-" + UUID.randomUUID();
    String canonical =
        String.join("\n", method, path, RequestSignatures.sha256Hex(body.getBytes(StandardCharsets.UTF_8)), ts, nonce,
            device.id());
    Signature s = Signature.getInstance("SHA256withECDSA");
    s.initSign(device.key().getPrivate());
    s.update(canonical.getBytes(StandardCharsets.UTF_8));
    return new Signed(device.id(), ts, nonce, Base64.getEncoder().encodeToString(s.sign()));
  }

  RequestPostProcessor token() {
    return jwt()
        .jwt(j -> j.subject("sub-" + msisdn).claim("msisdn", msisdn)
            .claim("role", "customer").claim("amr", List.of("pin", "device")));
  }

  MvcTestResult post(String path, String body, Signed signed, String idempotencyKey) {
    var req = mvc.post().uri(path).with(token()).contentType(MediaType.APPLICATION_JSON).content(body);
    if (signed != null) {
      req = req.header("X-Device-Id", signed.deviceId())
          .header("X-Timestamp", signed.timestamp())
          .header("X-Nonce", signed.nonce())
          .header("X-Signature", signed.signature());
    }
    if (idempotencyKey != null) {
      req = req.header("Idempotency-Key", idempotencyKey);
    }
    return req.exchange();
  }

  MvcTestResult submitSigned(Device device) throws Exception {
    return post("/app/v1/transactions", P2P, sign(device, "POST", "/app/v1/transactions", P2P), "key-" + UUID.randomUUID());
  }

  MvcTestResult status(String deviceId) {
    var req = mvc.get().uri("/app/v1/transactions/" + TXN_ID).with(token());
    if (deviceId != null) {
      req = req.header("X-Device-Id", deviceId);
    }
    return req.exchange();
  }

  static void assertCode(MvcTestResult r, int status, String code) {
    assertThat(r).hasStatus(status);
    assertThat(r).bodyJson().extractingPath("$.code").isEqualTo(code);
  }

  @Test
  void aBoundDeviceSubmitsASignedP2p() throws Exception {
    Device d = bind();
    assertThat(submitSigned(d)).hasStatus(202);
    verify(transactions, times(1)).submit(any(), any(), any());
  }

  @Test
  void theSameNonceReusedIsRejectedAndNothingIsSubmittedTwice() throws Exception {
    Device d = bind();
    Signed signed = sign(d, "POST", "/app/v1/transactions", P2P);
    String key = "key-" + UUID.randomUUID();

    assertThat(post("/app/v1/transactions", P2P, signed, key)).hasStatus(202);
    assertCode(post("/app/v1/transactions", P2P, signed, key), 401, "MW-AUTH-006");
    verify(transactions, times(1)).submit(any(), any(), any());
  }

  @Test
  void aTamperedBodyIsRejected() throws Exception {
    Device d = bind();
    Signed signed = sign(d, "POST", "/app/v1/transactions", P2P);
    assertCode(post("/app/v1/transactions", P2P.replace("1000", "9000"), signed, "key-t"), 401, "MW-AUTH-004");
  }

  /** The same JSON as {@link #P2P}, encoded differently (other key order, no spaces). */
  static final String P2P_REENCODED =
      "{\"amount\":{\"currency\":\"BDT\",\"valueMinor\":1000},\"txnType\":\"P2P\","
          + "\"encryptedPin\":{\"ciphertext\":\"Y2lwaGVy\",\"keyId\":\"core-pin-1\"},\"creditParty\":{\"id\":\"01711000002\"}}";

  @Test
  void aHashHeaderThatDoesNotMatchTheBodyIsIgnoredAndTheRequestRefused() throws Exception {
    Device d = bind();
    Signed signed = sign(d, "POST", "/app/v1/transactions", P2P);
    String tampered = P2P.replace("1000", "9000");
    // a forged hash of the signed body next to a body the device never signed
    var r = mvc.post().uri("/app/v1/transactions").with(token()).contentType(MediaType.APPLICATION_JSON)
        .content(tampered)
        .header("X-Device-Id", signed.deviceId())
        .header("X-Timestamp", signed.timestamp())
        .header("X-Nonce", signed.nonce())
        .header("X-Signature", signed.signature())
        .header("X-Mw-Body-Sha256", RequestSignatures.sha256Hex(P2P.getBytes(StandardCharsets.UTF_8)))
        .header("Idempotency-Key", "key-" + UUID.randomUUID())
        .exchange();

    assertCode(r, 401, "MW-AUTH-004");
    verify(transactions, times(0)).submit(any(), any(), any());
  }

  @Test
  void theSignatureCoversTheExactBytesNotJustTheSameJson() throws Exception {
    Device d = bind();
    Signed signed = sign(d, "POST", "/app/v1/transactions", P2P);
    assertCode(post("/app/v1/transactions", P2P_REENCODED, signed, "key-r"), 401, "MW-AUTH-004");
  }

  @Test
  void aSubmitBodyBreakingTheSchemaIsRefusedBeforeTheDeviceGate() {
    var r = mvc.post().uri("/app/v1/transactions").with(token()).contentType(MediaType.APPLICATION_JSON)
        .content("{\"txnType\":\"P2P\",\"creditParty\":{\"id\":\"01711000002\"},"
            + "\"encryptedPin\":{\"keyId\":\"k1\",\"ciphertext\":\"x\"}}")
        .header("Idempotency-Key", "key-" + UUID.randomUUID())
        .exchange();
    assertCode(r, 400, "MW-VAL-001");
    verify(transactions, times(0)).submit(any(), any(), any());
  }

  @Test
  void anUnsignedSubmitIsRejected() throws Exception {
    Device d = bind();
    var r = mvc.post().uri("/app/v1/transactions").with(token()).header("X-Device-Id", d.id())
        .header("Idempotency-Key", "key-u").contentType(MediaType.APPLICATION_JSON).content(P2P).exchange();
    assertCode(r, 401, "MW-AUTH-004");
  }

  @Test
  void anUnboundDeviceIsRejected() throws Exception {
    Device d = Device.create();
    assertCode(submitSigned(d), 401, "MW-AUTH-003");
    assertCode(status(d.id()), 401, "MW-AUTH-003");
    assertCode(status(null), 401, "MW-AUTH-003");
    assertCode(status("bad id!"), 401, "MW-AUTH-003");
  }

  @Test
  void aDeviceBoundToAnotherWalletIsRejected() throws Exception {
    Device d = bind();
    bound.put(d.id(), new BoundDevice(d.id(), "01799999999", d.publicKey(), Instant.now()));
    assertCode(submitSigned(d), 401, "MW-AUTH-003");
    assertCode(status(d.id()), 401, "MW-AUTH-003");
  }

  @Test
  void aTokenIssuedToAnotherDeviceIsRejected() throws Exception {
    Device d = bind();
    var r = mvc.get().uri("/app/v1/transactions/" + TXN_ID)
        .with(jwt().jwt(j -> j.subject(msisdn).claim("msisdn", msisdn).claim("role", "customer")
            .claim("amr", List.of("pin", "device")).claim("deviceId", "dev-other-device")))
        .header("X-Device-Id", d.id()).exchange();
    assertCode(r, 401, "MW-AUTH-003");
    var mine = mvc.get().uri("/app/v1/transactions/" + TXN_ID)
        .with(jwt().jwt(j -> j.subject(msisdn).claim("msisdn", msisdn).claim("role", "customer")
            .claim("amr", List.of("pin", "device")).claim("deviceId", d.id())))
        .header("X-Device-Id", d.id()).exchange();
    assertThat(mine).hasStatus(200);
  }

  @Test
  void withoutALiveSessionAtMwAuthTheDeviceIsRejected() throws Exception {
    Device d = bind();
    sessions.clear(); // the idle timeout, or another device bound
    assertCode(status(d.id()), 401, "MW-AUTH-007");
    assertCode(submitSigned(d), 401, "MW-AUTH-007");
    verify(transactions, times(0)).submit(any(), any(), any());

    sessions.add(msisdn + ":" + d.id()); // POST /auth/v1/sessions at mw-auth
    assertThat(status(d.id())).hasStatus(200);
  }

  @Test
  void theDeviceKeyIsCachedAndALiveSessionConfirmedOnlyBriefly() throws Exception {
    Device d = bind();
    assertThat(status(d.id())).hasStatus(200);
    assertThat(status(d.id())).hasStatus(200);
    assertThat(submitSigned(d)).hasStatus(202);

    verify(auth, times(1)).device(d.id());
    verify(auth, times(1)).session(eq(msisdn), eq(d.id()));
  }
}

package mfs.mw.app.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.ws.test.server.RequestCreators.withSoapEnvelope;

import java.io.ByteArrayOutputStream;
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
import java.util.concurrent.atomic.AtomicReference;
import mfs.mw.app.gateway.TestInfrastructure;
import mfs.mw.app.gateway.RedisInfrastructure;
import mfs.mw.app.gateway.controller.AppSoap;
import mfs.mw.app.gateway.filter.CachedBodyRequest;
import mfs.mw.app.gateway.client.AuthClient;
import mfs.mw.app.gateway.client.OrchestratorQuoteClient;
import mfs.mw.app.gateway.service.AllowingQuote;
import mfs.mw.app.gateway.dto.Amount;
import mfs.mw.app.gateway.dto.BalanceView;
import mfs.mw.app.gateway.dto.BoundDevice;
import mfs.mw.app.gateway.dto.LiveSession;
import mfs.mw.app.gateway.dto.SubmitResult;
import mfs.mw.app.gateway.dto.TransactionView;
import mfs.mw.app.gateway.service.BalanceService;
import mfs.mw.app.gateway.service.DeviceVerifier;
import mfs.mw.app.gateway.service.RequestSignatures;
import mfs.mw.app.gateway.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.ws.WebServiceMessage;
import org.springframework.ws.test.server.MockWebServiceClient;
import org.springframework.xml.transform.StringSource;

/**
 * The device gate as a verifier (spec 9.1, 18.6; ticket 29, slimmed in ticket 68) against real
 * Redis (device key cache, session confirmations, nonces): signed submits, replays, tampered or
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
      // the shared Testcontainers Redis; the shipped 500 ms is for production
      "spring.data.redis.timeout=3s"
    })
@AutoConfigureMockMvc
@Import({TestInfrastructure.class, RedisInfrastructure.class})
class DeviceBindingIT {

  static final String TXN_ID = "019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0d";
  static final String P2P =
      """
      {"txnType":"P2P","creditParty":{"id":"01711000002"},
       "amount":{"valueMinor":1000,"currency":"BDT"},
       "encryptedPin":{"keyId":"core-pin-1","ciphertext":"Y2lwaGVy"}}""";

  private static final String SOAP_NS =
      "xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:app=\"" + AppSoap.NAMESPACE + "\"";
  private static final long BALANCE_LEAK = 424_242L;

  private final MockMvcTester mvc;
  private final ApplicationContext context;

  @MockitoBean TransactionService transactions;
  @MockitoBean BalanceService balances;
  @MockitoBean AuthClient auth;
  /** Preview runs before the stubbed submit (ticket 105); the quote is not what this gate tests. */
  @MockitoBean OrchestratorQuoteClient quotes;

  /** mw-auth's state as the stub answers it: bound devices by id, live sessions as wallet:device. */
  private final Map<String, BoundDevice> bound = new ConcurrentHashMap<>();
  private final Set<String> sessions = ConcurrentHashMap.newKeySet();

  private String msisdn;

  @Autowired
  DeviceBindingIT(MockMvcTester mvc, ApplicationContext context) {
    this.mvc = mvc;
    this.context = context;
  }

  /** A wallet of its own per test, so tests never see each other's cached keys or sessions. */
  @BeforeEach
  void freshWallet() {
    msisdn = "0171" + String.format("%07d", (int) (Math.random() * 10_000_000));
    AllowingQuote.allow(quotes);
    when(transactions.submit(any(), any(), any()))
        .thenReturn(SubmitResult.processing(TransactionView.processing(TXN_ID)));
    when(transactions.find(any(), any())).thenReturn(TransactionView.processing(TXN_ID));
    when(balances.check(any(), any(), any())).thenReturn(
        new BalanceView(TXN_ID, new Amount(BALANCE_LEAK, "BDT"), Instant.parse("2026-11-20T06:00:00Z")));
    when(auth.device(anyString())).thenAnswer(i -> Optional.ofNullable(bound.get(i.<String>getArgument(0))));
    when(auth.session(anyString(), anyString())).thenAnswer(i -> {
      String w = i.getArgument(0);
      BoundDevice d = bound.get(i.<String>getArgument(1));
      return d != null && d.walletId().equals(w) && sessions.contains(w + ":" + d.deviceId())
          ? Optional.of(new LiveSession(w, d.deviceId(), 300, DeviceVerifier.fingerprint(d.publicKey()), d.boundAt(), 1L))
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

  /**
   * SOAP confirm and balance run on the SOAP servlet, so the MVC device interceptor does not see
   * them. A bearer and a PIN still need the live session, the device signature and a fresh nonce,
   * including after logout.
   */
  @Test
  void soapConfirmAndBalanceNeedTheSameDeviceGateAsRest() throws Exception {
    Device d = bind();
    assertThat(postSoap(d, null, confirmEnvelope("ignored-preview", "soap-unsigned"))).contains("MW-AUTH-004");
    assertThat(postSoap(d, null, balanceEnvelope("bal-unsigned"))).contains("MW-AUTH-004").doesNotContain("424242");
    verify(transactions, times(0)).submit(any(), any(), any());
    verify(balances, times(0)).check(any(), any(), any());

    Device loggedOut = bind();
    sessions.remove(msisdn + ":" + loggedOut.id());
    String loggedOutEnvelope = confirmEnvelope("ignored-preview", "soap-out");
    assertThat(postSoap(loggedOut, sign(loggedOut, "POST", "/app/ws/v1", loggedOutEnvelope), loggedOutEnvelope))
        .contains("MW-AUTH-007");
    String loggedOutBalance = balanceEnvelope("bal-out");
    assertThat(postSoap(loggedOut, sign(loggedOut, "POST", "/app/ws/v1", loggedOutBalance), loggedOutBalance))
        .contains("MW-AUTH-007")
        .doesNotContain("424242");
    verify(transactions, times(0)).submit(any(), any(), any());
    verify(balances, times(0)).check(any(), any(), any());

    String preview = postSoap(d, null, envelope("", previewBody()));
    assertThat(preview).doesNotContain("Fault");
    String previewId = text(preview, "previewId");
    String key = "soap-" + UUID.randomUUID();
    String envelope = confirmEnvelope(previewId, key);
    Signed signed = sign(d, "POST", "/app/ws/v1", envelope);
    assertThat(postSoap(d, signed, envelope)).doesNotContain("Fault");
    assertThat(postSoap(d, signed, envelope)).contains("MW-AUTH-006");
    verify(transactions, times(1)).submit(any(), any(), any());

    String balance = balanceEnvelope("bal-ok");
    assertThat(postSoap(d, sign(d, "POST", "/app/ws/v1", balance), balance)).contains("424242");
    verify(balances, times(1)).check(any(), any(), any());
  }

  private static String idempotency(String key) {
    return "<app:Idempotency-Key>" + key + "</app:Idempotency-Key>";
  }

  private static String previewBody() {
    return """
        <app:PreviewRequest>
          <app:group>send-money</app:group>
          <app:creditParty><app:id>01711000002</app:id></app:creditParty>
          <app:amount><app:valueMinor>1000</app:valueMinor><app:currency>BDT</app:currency></app:amount>
        </app:PreviewRequest>
        """;
  }

  private static String confirmBody(String previewId) {
    return """
        <app:ConfirmRequest>
          <app:group>send-money</app:group>
          <app:previewId>%s</app:previewId>
          <app:creditParty><app:id>01711000002</app:id></app:creditParty>
          <app:amount><app:valueMinor>1000</app:valueMinor><app:currency>BDT</app:currency></app:amount>
          <app:encryptedPin><app:keyId>core-pin-1</app:keyId><app:ciphertext>Y2lwaGVy</app:ciphertext></app:encryptedPin>
        </app:ConfirmRequest>
        """.formatted(previewId);
  }

  private static String balanceBody() {
    return """
        <app:GetBalanceRequest>
          <app:encryptedPin><app:keyId>core-pin-1</app:keyId><app:ciphertext>Y2lwaGVy</app:ciphertext></app:encryptedPin>
        </app:GetBalanceRequest>
        """;
  }

  private static String confirmEnvelope(String previewId, String key) {
    return envelope(idempotency(key), confirmBody(previewId));
  }

  private static String balanceEnvelope(String key) {
    return envelope(idempotency(key), balanceBody());
  }

  private static String envelope(String header, String body) {
    return """
        <soapenv:Envelope %s>
          <soapenv:Header>%s</soapenv:Header>
          <soapenv:Body>%s</soapenv:Body>
        </soapenv:Envelope>
        """.formatted(SOAP_NS, header, body);
  }

  /** {@code signed} null sends the device id and no signature. The hashed body is {@code envelope}. */
  private String postSoap(Device device, Signed signed, String envelope) throws Exception {
    byte[] bytes = envelope.getBytes(StandardCharsets.UTF_8);
    MockHttpServletRequest raw = new MockHttpServletRequest("POST", "/app/ws/v1");
    raw.setContent(bytes);
    raw.addHeader(SignedRequests.DEVICE_ID, device.id());
    if (signed != null) {
      raw.addHeader(SignedRequests.TIMESTAMP, signed.timestamp());
      raw.addHeader(SignedRequests.NONCE, signed.nonce());
      raw.addHeader(SignedRequests.SIGNATURE, signed.signature());
    }
    CachedBodyRequest cached = new CachedBodyRequest(raw, CachedBodyRequest.MAX_BODY);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(cached));
    Jwt jwt = Jwt.withTokenValue("soap-token")
        .header("alg", "none")
        .subject(msisdn)
        .claim("msisdn", msisdn)
        .claim("role", "customer")
        .claim("amr", List.of("pin"))
        .claim("deviceId", device.id())
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(300))
        .build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    try {
      AtomicReference<String> xml = new AtomicReference<>();
      MockWebServiceClient.createClient(context)
          .sendRequest(withSoapEnvelope(new StringSource(envelope)))
          .andExpect((request, response) -> xml.set(xml(response)));
      return xml.get();
    } finally {
      SecurityContextHolder.clearContext();
      RequestContextHolder.resetRequestAttributes();
    }
  }

  private static String xml(WebServiceMessage message) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      message.writeTo(out);
      return out.toString(StandardCharsets.UTF_8);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String text(String xml, String local) {
    int start = xml.indexOf("<" + local + ">");
    if (start < 0) {
      start = xml.indexOf(":" + local + ">");
      if (start < 0) {
        return "";
      }
      start = xml.lastIndexOf('<', start);
    }
    int from = xml.indexOf('>', start) + 1;
    return xml.substring(from, xml.indexOf("</", from)).strip();
  }
}

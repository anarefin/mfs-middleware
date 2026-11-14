package mfs.mw.checkout.client;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import mfs.mw.checkout.dto.ErrorDetail;
import mfs.mw.checkout.dto.OtpSent;
import mfs.mw.checkout.dto.TransportKey;
import mfs.mw.checkout.dto.TxnAuthorisationRequest;
import mfs.mw.checkout.exception.MwException;
import org.springframework.http.HttpStatus;

/**
 * Plays mw-auth behind a mocked {@link AuthClient} for in-module flow tests (ticket 72): one OTP per
 * (wallet, session), spent on a match (wrong {@code MW-AUTH-010}, none or spent {@code MW-AUTH-011});
 * the PIN ciphertext {@link #RIGHT_PIN} is right, anything else {@code 401 MW-AUTH-040}; an
 * assertion for every right PIN. Every authorisation request is recorded. {@link #refuseNext}
 * answers the next authorisation with a given refusal instead.
 */
public final class FakeAuth {

  public static final String RIGHT_PIN = "Y2lwaGVydGV4dA==";
  public static final String KEY_ID = "pin-transport.v1";
  public static final String ASSERTION = "eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiIwMTcxMTAwMDAwMSJ9.c2lnbmF0dXJl";

  private static final SecureRandom RANDOM = new SecureRandom();

  private final Map<String, String> otps = new ConcurrentHashMap<>();
  private final List<TxnAuthorisationRequest> authorisations = new CopyOnWriteArrayList<>();
  private volatile MwException nextRefusal;

  /** Installs the behaviour on the mock (call in {@code @BeforeEach}). */
  public FakeAuth install(AuthClient auth) {
    reset(auth);
    otps.clear();
    authorisations.clear();
    nextRefusal = null;
    when(auth.pinKey()).thenReturn(Optional.of(new TransportKey(KEY_ID, "RSA-OAEP-SHA-256",
        "-----BEGIN PUBLIC KEY-----\nMIIB\n-----END PUBLIC KEY-----\n")));
    when(auth.sendOtp(anyString(), any(), anyString(), anyString(), anyString())).thenAnswer(inv -> {
      String otp = String.format("%06d", RANDOM.nextInt(1_000_000));
      otps.put(key(inv.getArgument(0), inv.getArgument(1)), otp);
      return new OtpSent("0171****001", Instant.now().plusSeconds(120), 4);
    });
    doAnswer(inv -> {
      String k = key(inv.getArgument(0), inv.getArgument(1));
      String otp = otps.get(k);
      if (otp == null) {
        throw new MwException("MW-AUTH-011", "Ask for a new OTP");
      }
      if (!otp.equals(inv.getArgument(2))) {
        throw new MwException("MW-AUTH-010", "Wrong OTP");
      }
      if (!otps.remove(k, otp)) {
        throw new MwException("MW-AUTH-011", "Ask for a new OTP");
      }
      return null;
    }).when(auth).verifyOtp(anyString(), any(), anyString());
    when(auth.authorise(any())).thenAnswer(inv -> {
      TxnAuthorisationRequest req = inv.getArgument(0);
      authorisations.add(req);
      MwException refusal = nextRefusal;
      if (refusal != null) {
        nextRefusal = null;
        throw refusal;
      }
      if (!RIGHT_PIN.equals(req.encryptedPin().ciphertext())) {
        throw new MwException("MW-AUTH-040", "Wrong PIN").withStatus(HttpStatus.UNAUTHORIZED)
            .withDetails(List.of(new ErrorDetail("triesLeft", "2")));
      }
      return ASSERTION;
    });
    return this;
  }

  /** The OTP mw-auth "sent" for the wallet and session. */
  public String otp(String msisdn, String sessionId) {
    return otps.get(key(msisdn, UUID.fromString(sessionId)));
  }

  public List<TxnAuthorisationRequest> authorisations() {
    return List.copyOf(authorisations);
  }

  public void refuseNext(MwException refusal) {
    nextRefusal = refusal;
  }

  private static String key(String msisdn, Object sessionId) {
    return msisdn + "|" + sessionId;
  }
}

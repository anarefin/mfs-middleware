package mfs.mw.crypto.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PreDestroy;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import mfs.mw.crypto.config.PayShieldProperties;
import mfs.mw.crypto.exception.CryptoErrors;
import mfs.mw.crypto.exception.ErrorCodes;
import mfs.mw.crypto.exception.MwException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The Thales payShield 10K provider (ticket 64, spec 18.8, D23): host commands over TCP, in dev and
 * test against the simulator in mw-simulators. Each message is a 2-byte big-endian length, a header
 * ({@code header-length} digits, a rolling counter) and the command; the answer must carry the same
 * header (request/response correlation) and the command's response code, else the connection is
 * dropped and the request fails closed. A pool of at most {@code pool-size} persistent connections,
 * one command in flight on each; {@code call-timeout} per command; transport failures (connect,
 * timeout, closed connection, wrong header) count in the Resilience4j circuit breaker {@code
 * payshield}, error codes in an answer do not. Commands: {@code A0} (keys under the LMK: the
 * returned blob is the key version's HSM reference; this service never sees a clear key), {@code CA}
 * / {@code CC} (translate), {@code JC} / {@code JE} + {@code DG} (PVV), {@code DC} / {@code EC}
 * (verify), {@code NC} (probe). Message content is never logged. Field layouts: mw-simulators
 * CLAUDE.md "payShield simulator".
 */
@Component
@ConditionalOnProperty(name = "mw.crypto.payshield.enabled", havingValue = "true")
public class PayShieldProvider implements CryptoProvider, PinBlockProvider {

  public static final String NAME = "payshield";
  private static final Logger log = LoggerFactory.getLogger(PayShieldProvider.class);

  private final PayShieldProperties props;
  private final CircuitBreaker breaker;
  private final Semaphore permits;
  private final ConcurrentLinkedDeque<Connection> idle = new ConcurrentLinkedDeque<>();
  private final AtomicInteger counter = new AtomicInteger();
  private final int headerModulus;

  public PayShieldProvider(PayShieldProperties props, CircuitBreakerRegistry breakers) {
    this.props = props;
    this.breaker = breakers.circuitBreaker(NAME);
    this.permits = new Semaphore(props.poolSize(), true);
    this.headerModulus = (int) Math.pow(10, Math.min(props.headerLength(), 9));
  }

  /** One HSM connection. */
  private record Connection(Socket socket, DataInputStream in, DataOutputStream out) {
    void close() {
      try {
        socket.close();
      } catch (IOException e) {
        // closing anyway
      }
    }
  }

  /** A failed exchange: transport (counted by the breaker) or a refused answer. */
  private static final class TransportException extends IOException {
    TransportException(String message) {
      super(message);
    }
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Set<Capability> capabilities() {
    return EnumSet.of(Capability.PIN_BLOCK, Capability.PVV);
  }

  @Override
  public void generateKey(String label, KeyAlgorithm algorithm) {
    throw unsupported("generate without a purpose");
  }

  @Override
  public String createKey(String label, KeyAlgorithm algorithm, String purpose) {
    String type = purpose.startsWith("ZPK") ? "001" : "002";
    char scheme = switch (algorithm) {
      case TDES_2KEY -> 'U';
      case AES_128 -> 'S';
      default -> throw unsupported("algorithm " + algorithm);
    };
    String fields = ok("generate", command("generate", "A0", "0" + type + scheme));
    return fields.substring(0, 33);
  }

  @Override
  public String translate(PinKey source, String block, PinBlockFormat from, PinKey dest, PinBlockFormat to,
      String account) {
    String cmd = source.terminal() ? "CA" : "CC";
    String fields = ok("pinblock-translate", command("pinblock-translate", cmd,
        source.ref() + dest.ref() + "12" + block + from.code() + to.code() + accountField(account, from, to)));
    // PIN length (2), the block, its format code (2)
    return fields.substring(2, 2 + to.hexLength());
  }

  @Override
  public String generatePvv(String pvkRef, PinKey key, String block, PinBlockFormat format, String account, int pvki) {
    String cmd = key.terminal() ? "JC" : "JE";
    String pinUnderLmk = ok("pvv-generate", command("pvv-generate", cmd,
        key.ref() + block + format.code() + accountField(account, format, format)));
    String pvv = ok("pvv-generate", command("pvv-generate", "DG", pvkRef + pinUnderLmk + account12(account) + pvki));
    if (!pvv.matches("[0-9]{4}")) {
      throw CryptoErrors.hsmUnavailable("pvv-generate (malformed answer)", null);
    }
    return pvv;
  }

  @Override
  public boolean verifyPvv(String pvkRef, PinKey key, String block, PinBlockFormat format, String account, int pvki,
      String pvv) {
    String cmd = key.terminal() ? "DC" : "EC";
    String answer = command("pvv-verify", cmd,
        key.ref() + pvkRef + block + format.code() + accountField(account, format, format) + pvki + pvv);
    if (answer.startsWith("01")) {
      return false;
    }
    ok("pvv-verify", answer);
    return true;
  }

  @Override
  public boolean probe() {
    try {
      ok("probe", command("probe", "NC", ""));
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  @Override
  public PublicKey publicKey(String label) {
    throw unsupported("public keys");
  }

  @Override
  public void destroy(String label) {
    // a payShield key is only its blob under the LMK: nothing to destroy in the HSM
  }

  @Override
  public byte[] decryptOaep(String label, byte[] ciphertext) {
    throw unsupported("OAEP");
  }

  @Override
  public List<byte[]> hmac(String label, List<byte[]> messages) {
    throw unsupported("HMAC");
  }

  @Override
  public byte[] sign(String label, SignatureAlgorithm algorithm, byte[] data) {
    throw unsupported("signatures");
  }

  @Override
  public byte[] wrapKey(String label, byte[] dataKey) {
    throw unsupported("key wrapping");
  }

  @Override
  public byte[] unwrapKey(String label, byte[] wrapped) {
    throw unsupported("key wrapping");
  }

  /** The ISO 9564 account field: 12 digits, or a length and the whole PAN when format 4 is involved. */
  static String accountField(String account, PinBlockFormat a, PinBlockFormat b) {
    if (a == PinBlockFormat.ISO4 || b == PinBlockFormat.ISO4) {
      return String.format("%02d", account.length()) + account;
    }
    return account12(account);
  }

  /** The 12 rightmost digits without the check digit (left-padded with zeros). */
  static String account12(String account) {
    String body = account.substring(0, account.length() - 1);
    return body.length() >= 12 ? body.substring(body.length() - 12) : "0".repeat(12 - body.length()) + body;
  }

  /** The answer's error code and fields ({@code 00...}) for command {@code code} with {@code fields}. */
  private String command(String op, String code, String fields) {
    String expected = "" + code.charAt(0) + (char) (code.charAt(1) + 1);
    String answer;
    try {
      answer = breaker.executeCheckedSupplier(() -> exchange(code + fields));
    } catch (CallNotPermittedException e) {
      throw CryptoErrors.hsmUnavailable(op + " (circuit open)", e);
    } catch (Throwable e) {
      throw CryptoErrors.hsmUnavailable(op, e);
    }
    if (answer.length() < 4 || !answer.startsWith(expected)) {
      throw CryptoErrors.hsmUnavailable(op + " (unexpected answer)", null);
    }
    return answer.substring(2);
  }

  /** The fields after error code 00, or the error mapped (ticket 64, CLAUDE.md "payShield provider"). */
  private static String ok(String op, String answer) {
    String error = answer.substring(0, 2);
    switch (error) {
      case "00" -> {
        return answer.substring(2);
      }
      case "10", "11" -> throw CryptoErrors.keyNotUsable("(payShield key, parity error " + error + ")", op);
      case "15" -> throw op.startsWith("pin") || op.startsWith("pvv")
          ? CryptoErrors.badPinBlock()
          : CryptoErrors.invalidField("request", "refused by the HSM");
      default -> throw CryptoErrors.hsmUnavailable(op + " (payShield error " + error + ")", null);
    }
  }

  private String exchange(String body) throws IOException, InterruptedException {
    if (!permits.tryAcquire(props.acquireTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
      throw new TransportException("no free HSM connection");
    }
    Connection c = null;
    try {
      c = idle.pollFirst();
      if (c == null) {
        c = open();
      }
      String header = String.format("%0" + props.headerLength() + "d", Math.floorMod(counter.getAndIncrement(),
          headerModulus));
      byte[] message = (header + body).getBytes(StandardCharsets.ISO_8859_1);
      c.out().writeShort(message.length);
      c.out().write(message);
      c.out().flush();
      byte[] reply = new byte[c.in().readUnsignedShort()];
      c.in().readFully(reply);
      String text = new String(reply, StandardCharsets.ISO_8859_1);
      if (!text.startsWith(header)) {
        throw new TransportException("answer for another request");
      }
      idle.addFirst(c);
      c = null;
      return text.substring(props.headerLength());
    } catch (IOException e) {
      log.atWarn().addKeyValue("event", "crypto.hsm.call_failed").addKeyValue("provider", NAME)
          .addKeyValue("error", e.getClass().getSimpleName()).log("payShield exchange failed; connection dropped");
      throw e;
    } finally {
      if (c != null) {
        c.close();
      }
      permits.release();
    }
  }

  private Connection open() throws IOException {
    Socket s = new Socket();
    try {
      s.connect(new InetSocketAddress(props.host(), props.port()), (int) props.connectTimeout().toMillis());
      s.setSoTimeout((int) props.callTimeout().toMillis());
      s.setTcpNoDelay(true);
      s.setKeepAlive(true);
      return new Connection(s, new DataInputStream(s.getInputStream()), new DataOutputStream(s.getOutputStream()));
    } catch (IOException e) {
      s.close();
      throw e;
    }
  }

  @PreDestroy
  void shutdown() {
    Connection c;
    while ((c = idle.pollFirst()) != null) {
      c.close();
    }
  }

  private static MwException unsupported(String what) {
    return new MwException(ErrorCodes.SYS_INTERNAL, "payShield provider does not support " + what);
  }
}

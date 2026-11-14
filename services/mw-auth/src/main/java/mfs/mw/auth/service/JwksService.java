package mfs.mw.auth.service;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mfs.mw.auth.client.CryptoClient;
import mfs.mw.auth.config.TokenProperties;
import mfs.mw.auth.dto.PinKey;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The public keys mw-auth hands out, from mw-crypto's JWK Sets (spec 18.7, 18.8; ticket 69), each
 * cached {@code mw.auth.tokens.jwks-cache-ttl}:
 *
 * <ul>
 *   <li>{@code JWT_SIGN}: every version in use, public members only ({@code GET
 *       /auth/v1/.well-known/jwks.json}, and this service's own verifier). A token with a kid not in
 *       the cached set fetches it again at once (at most every {@link #MIN_REFRESH}), so a rotated key
 *       works without waiting for the cache.
 *   <li>{@code PIN_TRANSPORT}: the newest version as PEM ({@code GET /auth/v1/pin-key}).
 * </ul>
 *
 * mw-crypto down and nothing cached: 503 MW-SYS-050.
 */
@Service
public class JwksService {

  static final Duration MIN_REFRESH = Duration.ofSeconds(10);
  private static final Set<String> PUBLIC_MEMBERS = Set.of("kty", "kid", "alg", "use", "n", "e", "crv", "x", "y");
  private static final Pattern VERSION = Pattern.compile("\\.v([0-9]+)$");

  private record Cached<T>(T value, Instant at) {}

  private final CryptoClient crypto;
  private final TokenProperties props;
  private final Clock clock;
  private volatile Cached<Map<String, Object>> signing;
  private volatile Cached<PinKey> pinKey;

  public JwksService(CryptoClient crypto, TokenProperties props, Clock clock) {
    this.crypto = crypto;
    this.props = props;
    this.clock = clock;
  }

  /** The JWT_SIGN JWK Set as served at {@code /.well-known/jwks.json}. */
  public Map<String, Object> signingJwks() {
    Cached<Map<String, Object>> c = signing;
    if (c == null || expired(c.at(), props.jwksCacheTtl())) {
      c = fetchSigning();
    }
    return c.value();
  }

  /** The JWT_SIGN key set for a verifier; refetched (rate limited) when {@code kid} is not in it. */
  public JWKSet signingKeySet(String kid) {
    JWKSet set = parse(signingJwks());
    if (kid != null && set.getKeyByKeyId(kid) == null) {
      Cached<Map<String, Object>> c = signing;
      if (c == null || expired(c.at(), MIN_REFRESH)) {
        set = parse(fetchSigning().value());
      }
    }
    return set;
  }

  /** The newest PIN_TRANSPORT key version. */
  public PinKey pinKey() {
    Cached<PinKey> c = pinKey;
    if (c == null || expired(c.at(), props.jwksCacheTtl())) {
      c = new Cached<>(newestPinKey(crypto.jwks("PIN_TRANSPORT")), clock.instant());
      pinKey = c;
    }
    return c.value();
  }

  private synchronized Cached<Map<String, Object>> fetchSigning() {
    Map<String, Object> raw = crypto.jwks("JWT_SIGN");
    List<Map<String, Object>> keys = new ArrayList<>();
    for (Object k : (List<?>) raw.get("keys")) {
      if (k instanceof Map<?, ?> m) {
        Map<String, Object> pub = new LinkedHashMap<>();
        m.forEach((name, value) -> {
          if (PUBLIC_MEMBERS.contains(String.valueOf(name))) {
            pub.put(String.valueOf(name), value);
          }
        });
        keys.add(pub);
      }
    }
    Cached<Map<String, Object>> c = new Cached<>(Map.of("keys", List.copyOf(keys)), clock.instant());
    signing = c;
    return c;
  }

  private static PinKey newestPinKey(Map<String, Object> set) {
    Map<?, ?> newest = null;
    int best = -1;
    for (Object k : (List<?>) set.get("keys")) {
      if (k instanceof Map<?, ?> m && "RSA".equals(m.get("kty")) && m.get("kid") instanceof String kid) {
        Matcher v = VERSION.matcher(kid);
        int n = v.find() ? Integer.parseInt(v.group(1)) : 0;
        if (n > best) {
          best = n;
          newest = m;
        }
      }
    }
    if (newest == null) {
      throw new MwException(ErrorCodes.SYS_CRYPTO_UNAVAILABLE, "No PIN transport key")
          .withStatus(HttpStatus.SERVICE_UNAVAILABLE);
    }
    try {
      @SuppressWarnings("unchecked")
      RSAKey key = RSAKey.parse((Map<String, Object>) newest);
      String b64 = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(key.toRSAPublicKey().getEncoded());
      return new PinKey(key.getKeyID(), "RSA-OAEP-SHA-256",
          "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----\n");
    } catch (ParseException | com.nimbusds.jose.JOSEException e) {
      throw new MwException(ErrorCodes.SYS_CRYPTO_UNAVAILABLE, "PIN transport key not readable", e)
          .withStatus(HttpStatus.SERVICE_UNAVAILABLE);
    }
  }

  private static JWKSet parse(Map<String, Object> set) {
    try {
      return JWKSet.parse(set);
    } catch (ParseException e) {
      throw new MwException(ErrorCodes.SYS_CRYPTO_UNAVAILABLE, "JWK Set not readable", e)
          .withStatus(HttpStatus.SERVICE_UNAVAILABLE);
    }
  }

  private boolean expired(Instant at, Duration ttl) {
    return !clock.instant().isBefore(at.plus(ttl));
  }
}

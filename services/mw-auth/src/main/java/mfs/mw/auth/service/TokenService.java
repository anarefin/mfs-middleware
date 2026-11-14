package mfs.mw.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.auth.client.CryptoClient;
import mfs.mw.auth.config.TokenProperties;
import mfs.mw.auth.dto.TokenPair;
import mfs.mw.auth.entity.AuthWallet;
import mfs.mw.auth.entity.RefreshToken;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import mfs.mw.auth.repository.RefreshTokenRepository;

/**
 * The wallet users' tokens (spec 18.7, D21; ticket 69).
 *
 * <ul>
 *   <li>Access JWT: RS256 by the mw-crypto key {@code JWT_SIGN} (header {@code typ at+jwt}, {@code
 *       kid <keyId>.v<n>}), {@code exp} = {@code iat} + 5 min; claims {@code iss}, {@code aud}, {@code
 *       sub} walletId, {@code msisdn}, {@code role} (from the wallet type), {@code deviceId}, {@code amr},
 *       {@code sid} (the refresh family), {@code jti}.
 *   <li>Refresh token: 32 random bytes, base64url; only its SHA-256 (hex) is stored, with its family,
 *       wallet, device, expiry (30 days) and the session's {@code amr}. Every use rotates it ({@link
 *       #rotate}); a rotated (or revoked) token used again revokes the whole family ({@code
 *       MW-AUTH-048}); a token of another device revokes it too ({@code MW-AUTH-047}).
 * </ul>
 */
@Service
public class TokenService {

  private static final Logger log = LoggerFactory.getLogger(TokenService.class);
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Map<String, String> ROLES =
      Map.of("CUSTOMER", "customer", "AGENT", "agent", "MERCHANT", "merchant", "DISTRIBUTOR", "distributor",
          "DSO", "dso");

  private final CryptoClient crypto;
  private final RefreshTokenRepository refresh;
  private final TokenProperties props;
  private final Clock clock;

  public TokenService(CryptoClient crypto, RefreshTokenRepository refresh, TokenProperties props, Clock clock) {
    this.crypto = crypto;
    this.refresh = refresh;
    this.props = props;
    this.clock = clock;
  }

  /** The app role of a wallet type; 403 MW-AUTH-002 for a type that has no app (ORG). */
  public static String role(String walletType) {
    String role = ROLES.get(walletType);
    if (role == null) {
      throw new MwException(ErrorCodes.AUTH_FORBIDDEN, "This wallet cannot use the app").withStatus(HttpStatus.FORBIDDEN);
    }
    return role;
  }

  /** A new session (refresh family) and its first tokens. */
  public TokenPair issue(AuthWallet wallet, String msisdn, String deviceId, List<String> amr) {
    return issue(wallet, msisdn, deviceId, amr, UUID.randomUUID());
  }

  /** Tokens of the session {@code family} (a rotation keeps it). */
  public TokenPair issue(AuthWallet wallet, String msisdn, String deviceId, List<String> amr, UUID family) {
    String role = role(wallet.type());
    Instant now = clock.instant();
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", props.issuer());
    claims.put("aud", props.audience());
    claims.put("sub", wallet.walletId());
    claims.put("msisdn", msisdn);
    claims.put("role", role);
    claims.put("deviceId", deviceId);
    claims.put("amr", List.copyOf(amr));
    claims.put("sid", family.toString());
    claims.put("jti", UUID.randomUUID().toString());
    claims.put("iat", now.getEpochSecond());
    claims.put("exp", now.plus(props.accessTtl()).getEpochSecond());
    String access = crypto.signJws("JWT_SIGN", "RS256", Map.of("typ", "at+jwt"), claims).jws();

    byte[] raw = new byte[32];
    RANDOM.nextBytes(raw);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    refresh.insert(hash(token), family, wallet.walletId(), deviceId, now.plus(props.refreshTtl()), amr);
    return new TokenPair(access, "Bearer", props.accessTtl().toSeconds(), token, props.refreshTtl().toSeconds(),
        family.toString(), 0, true);
  }

  /**
   * Rotates a refresh token presented by {@code deviceId}: the current row (now rotated), whose
   * family and methods the new tokens keep. 401 MW-AUTH-048 (family revoked) for a token already
   * rotated or revoked, MW-AUTH-047 (family revoked) for a token of another device, MW-AUTH-001 for
   * an unknown or expired one.
   */
  public RefreshToken rotate(String token, String deviceId) {
    String h = hash(token);
    RefreshToken current = refresh.rotate(h, clock.instant()).orElse(null);
    if (current == null) {
      RefreshToken seen = refresh.find(h).orElse(null);
      if (seen != null && (seen.rotatedAt() != null || seen.revoked())) {
        refresh.revokeFamily(seen.family());
        log.atWarn().addKeyValue("event", "auth.refresh.reused").addKeyValue("errorCode", ErrorCodes.AUTH_REFRESH_REUSED)
            .log("A used refresh token was presented again; session {} revoked", seen.family());
        throw new MwException(ErrorCodes.AUTH_REFRESH_REUSED, "Refresh token already used; sign in again");
      }
      throw new MwException(ErrorCodes.AUTH_UNAUTHENTICATED, "Refresh token not valid; sign in again");
    }
    if (!current.deviceId().equals(deviceId)) {
      refresh.revokeFamily(current.family());
      log.atWarn().addKeyValue("event", "auth.refresh.wrong_device").addKeyValue("errorCode", ErrorCodes.AUTH_DEVICE_MISMATCH)
          .log("A refresh token of device {} came from device {}; session {} revoked", current.deviceId(), deviceId,
              current.family());
      throw new MwException(ErrorCodes.AUTH_DEVICE_MISMATCH, "Device not bound to this session");
    }
    return current;
  }

  /** Ends a session's refresh family. */
  public void revoke(UUID family) {
    refresh.revokeFamily(family);
  }

  public static String hash(String token) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}

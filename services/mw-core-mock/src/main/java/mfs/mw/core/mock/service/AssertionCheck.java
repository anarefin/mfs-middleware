package mfs.mw.core.mock.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import mfs.mw.core.mock.config.AssertionProperties;
import mfs.mw.core.mock.dto.Party;
import mfs.mw.core.mock.dto.TransferPayload;
import mfs.mw.core.mock.entity.Wallet;
import mfs.mw.core.mock.repository.UsedAssertionRepository;
import mfs.mw.core.mock.security.AssertionKeys;
import mfs.mw.core.mock.util.TxnHash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * mw-auth's transaction assertion on a transfer or hold (spec 18.7, D22; ticket 71), in place of
 * the PIN: a compact JWS, ES256 only, signed by a key of mw-auth's assertion set ({@link
 * AssertionKeys}); {@code iss} mw-auth, {@code aud} core, {@code authMethod} PIN, {@code sub} the
 * authorising wallet (the payer; the payee for money coming in), {@code txnHash} equal to the hash
 * recomputed from the command ({@link TxnHash}: txnType, amount, currency, debit and credit party,
 * the payload's idempotencyKey, the assertion's channel), and a {@code jti} no other transaction
 * used. The first command of a transaction to carry it records the jti ({@code used_assertion}, in
 * the command transaction) and needs it unexpired ({@code exp} + {@code clock-skew}); the later
 * commands of the same transaction (PostTransfer after ValidateTransaction) are authorised by that
 * record. Any failure is {@link Reason#ASSERTION_INVALID} (MW-AUTH-046), logged WARN {@code
 * core.assertion.refused} with the cause; the token itself is never logged.
 */
@Component
class AssertionCheck {

  private static final Logger log = LoggerFactory.getLogger(AssertionCheck.class);

  private final AssertionKeys keys;
  private final UsedAssertionRepository used;
  private final AssertionProperties props;
  private final Clock clock;

  AssertionCheck(AssertionKeys keys, UsedAssertionRepository used, AssertionProperties props, Clock clock) {
    this.keys = keys;
    this.used = used;
    this.props = props;
    this.clock = clock;
  }

  /** Empty when the assertion authorises this transfer by {@code wallet} under {@code txnId}. */
  Optional<Reason> check(TransferPayload p, Wallet wallet, String txnId) {
    String refused = refusal(p, wallet, txnId);
    if (refused == null) {
      return Optional.empty();
    }
    log.atWarn()
        .addKeyValue("event", "core.assertion.refused")
        .addKeyValue("txnId", txnId)
        .addKeyValue("errorCode", Reason.ASSERTION_INVALID.code())
        .addKeyValue("cause", refused)
        .log("transaction assertion refused");
    return Optional.of(Reason.ASSERTION_INVALID);
  }

  private String refusal(TransferPayload p, Wallet wallet, String txnId) {
    SignedJWT jwt;
    JWTClaimsSet c;
    try {
      jwt = SignedJWT.parse(p.assertion());
      if (!JWSAlgorithm.ES256.equals(jwt.getHeader().getAlgorithm())) {
        return "ALGORITHM";
      }
      Optional<ECKey> key = keys.key(jwt.getHeader().getKeyID());
      if (key.isEmpty()) {
        return "UNKNOWN_KEY";
      }
      if (!jwt.verify(new ECDSAVerifier(key.get()))) {
        return "SIGNATURE";
      }
      c = jwt.getJWTClaimsSet();
    } catch (ParseException | JOSEException e) {
      return "MALFORMED";
    }
    if (!props.issuer().equals(c.getIssuer())) {
      return "ISSUER";
    }
    if (c.getAudience() == null || !c.getAudience().contains(props.audience())) {
      return "AUDIENCE";
    }
    if (!wallet.id().equals(c.getSubject())) {
      return "SUBJECT";
    }
    String channel;
    String hash;
    try {
      channel = c.getStringClaim("channel");
      hash = c.getStringClaim("txnHash");
      if (!"PIN".equals(c.getStringClaim("authMethod"))) {
        return "AUTH_METHOD";
      }
    } catch (ParseException e) {
      return "MALFORMED";
    }
    if (channel == null || hash == null || !hash.equals(recompute(p, channel))) {
      return "TXN_HASH";
    }
    String jti = c.getJWTID();
    Date exp = c.getExpirationTime();
    if (jti == null || exp == null) {
      return "MALFORMED";
    }
    Optional<String> usedBy = used.txnOf(jti);
    if (usedBy.isPresent()) {
      return usedBy.get().equals(txnId) ? null : "REPLAYED";
    }
    Instant now = clock.instant();
    if (now.isAfter(exp.toInstant().plus(props.clockSkew()))) {
      return "EXPIRED";
    }
    Date iat = c.getIssueTime();
    if (iat != null && iat.toInstant().isAfter(now.plus(props.clockSkew()))) {
      return "NOT_YET_VALID";
    }
    return used.claim(jti, txnId, exp.toInstant()).equals(txnId) ? null : "REPLAYED";
  }

  private static String recompute(TransferPayload p, String channel) {
    return TxnHash.of(p.txnType(), p.amount().valueMinor(), p.amount().currency(), party(p.debitParty()),
        party(p.creditParty()), p.idempotencyKey(), channel);
  }

  private static TxnHash.Party party(Party p) {
    return new TxnHash.Party(p.type(), p.id());
  }
}

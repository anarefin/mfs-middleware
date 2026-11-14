package mfs.mw.crypto.service;

import java.util.Locale;
import java.util.function.Predicate;
import mfs.mw.crypto.client.Capability;
import mfs.mw.crypto.client.KeyAlgorithm;
import mfs.mw.crypto.client.PinBlockFormat;
import mfs.mw.crypto.client.PinBlockProvider;
import mfs.mw.crypto.client.PinBlockProvider.PinKey;
import mfs.mw.crypto.config.PayShieldProperties;
import mfs.mw.crypto.dto.PinBlockInput;
import mfs.mw.crypto.dto.PvvRequest;
import mfs.mw.crypto.dto.PvvView;
import mfs.mw.crypto.dto.TranslatePinBlockRequest;
import mfs.mw.crypto.dto.TranslatedPinBlock;
import mfs.mw.crypto.exception.CryptoErrors;
import mfs.mw.crypto.security.CallerPolicy;
import mfs.mw.crypto.service.HsmKeys.Resolved;
import mfs.mw.crypto.service.HsmKeys.Use;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * ISO 9564 PIN block translation and Visa PVV on the payShield provider (ticket 64, spec 18.8; for
 * the later ATM and interop adapters). Before any HSM call: each key is resolved (keyId = its ACTIVE
 * version, a kid that version; translation targets and PVV generation take ACTIVE only), checked for
 * its purpose (source TPK/ZPK, target ZPK, PVK), the format against the key ({@code ISO4} needs an
 * {@code _AES} key, the others a triple DES one; else {@code MW-VAL-015}) and the block length
 * against the format ({@code MW-VAL-001}), the format pair against {@code
 * mw.crypto.payshield.allowed-translations} (never a PAN-bound format into ISO 1: {@code
 * MW-VAL-015}), and the caller against every key's purpose ({@code security/CallerPolicy}, deny by
 * default: no caller is bound yet). PIN attempt counting and lockout are the caller's job, and the
 * account must come from the caller's own records (the ATM / interop adapter resolves it server
 * side), never from an untrusted client. The clear PIN never leaves the
 * HSM; nothing here logs a block, PVV or account.
 */
@Service
public class PinBlockService {

  private static final Logger log = LoggerFactory.getLogger(PinBlockService.class);
  private static final Predicate<KeyPurpose> PIN_KEY = p -> p.capability() == Capability.PIN_BLOCK;
  private static final Predicate<KeyPurpose> ZONE_KEY = p -> PIN_KEY.test(p) && p.name().startsWith("ZPK");
  private static final Predicate<KeyPurpose> PVK = p -> p.capability() == Capability.PVV;

  private final HsmKeys keys;
  private final CallerPolicy callers;
  private final PayShieldProperties props;

  public PinBlockService(HsmKeys keys, CallerPolicy callers, PayShieldProperties props) {
    this.keys = keys;
    this.callers = callers;
    this.props = props;
  }

  public TranslatedPinBlock translate(TranslatePinBlockRequest r) {
    String op = "pinblock-translate";
    Resolved source = keys.resolve(r.source().keyId(), Use.CHECK, PIN_KEY, op);
    Resolved dest = keys.resolve(r.destination().keyId(), Use.CREATE, ZONE_KEY, op);
    callers.requirePurpose(source.purpose().name(), op);
    callers.requirePurpose(dest.purpose().name(), op);
    String block = block(r.source(), source, op);
    fits(r.destination().format(), dest, op);
    if (!props.translationAllowed(r.source().format(), r.destination().format())) {
      // a PAN-bound block (ISO 0, 3, 4) must never come out as ISO 1, bound to no account
      throw CryptoErrors.keyNotUsable(dest.kid(), op + " from " + r.source().format() + " to "
          + r.destination().format() + " (not an allowed translation)");
    }
    PinBlockProvider hsm = pinBlockProvider(source, op);
    sameProvider(source, dest, op);
    String out = keys.run(op, source, p -> hsm.translate(pinKey(source), block, r.source().format(), pinKey(dest),
        r.destination().format(), r.account()));
    log.atInfo().addKeyValue("event", "crypto.pinblock.translated").addKeyValue("kid", dest.kid())
        .log("PIN block translated");
    return new TranslatedPinBlock(out.toUpperCase(Locale.ROOT), r.destination().format(), dest.kid());
  }

  public PvvView generatePvv(PvvRequest r) {
    String op = "pvv-generate";
    Resolved pvk = keys.resolve(r.pvkId(), Use.CREATE, PVK, op);
    Resolved pin = keys.resolve(r.pinBlock().keyId(), Use.CHECK, PIN_KEY, op);
    callers.requirePurpose(pvk.purpose().name(), op);
    callers.requirePurpose(pin.purpose().name(), op);
    String block = block(r.pinBlock(), pin, op);
    PinBlockProvider hsm = pinBlockProvider(pvk, op);
    sameProvider(pvk, pin, op);
    String pvv = keys.run(op, pvk, p -> hsm.generatePvv(pvk.label(), pinKey(pin), block, r.pinBlock().format(),
        r.account(), r.pvki()));
    return PvvView.generated(pvv, pvk.kid(), r.pvki());
  }

  public PvvView verifyPvv(PvvRequest r) {
    String op = "pvv-verify";
    if (r.pvv() == null) {
      throw CryptoErrors.invalidField("pvv", "required");
    }
    Resolved pvk = keys.resolve(r.pvkId(), Use.CHECK, PVK, op);
    Resolved pin = keys.resolve(r.pinBlock().keyId(), Use.CHECK, PIN_KEY, op);
    callers.requirePurpose(pvk.purpose().name(), op);
    callers.requirePurpose(pin.purpose().name(), op);
    String block = block(r.pinBlock(), pin, op);
    PinBlockProvider hsm = pinBlockProvider(pvk, op);
    sameProvider(pvk, pin, op);
    boolean match = keys.run(op, pvk, p -> hsm.verifyPvv(pvk.label(), pinKey(pin), block, r.pinBlock().format(),
        r.account(), r.pvki(), r.pvv()));
    return PvvView.verified(match, pvk.kid());
  }

  /** The block, upper case, after its format fits the key and its length fits the format. */
  private static String block(PinBlockInput in, Resolved key, String op) {
    fits(in.format(), key, op);
    if (in.block().length() != in.format().hexLength()) {
      throw CryptoErrors.invalidField("block", "length does not fit the format");
    }
    return in.block().toUpperCase(Locale.ROOT);
  }

  private static void fits(PinBlockFormat format, Resolved key, String op) {
    boolean aes = key.purpose().algorithm() == KeyAlgorithm.AES_128;
    if ((format == PinBlockFormat.ISO4) != aes) {
      throw CryptoErrors.keyNotUsable(key.kid(), op + " in " + format);
    }
  }

  private static PinKey pinKey(Resolved key) {
    return new PinKey(key.label(), key.purpose().name().startsWith("TPK"));
  }

  private static PinBlockProvider pinBlockProvider(Resolved key, String op) {
    if (!(key.provider() instanceof PinBlockProvider p)) {
      throw CryptoErrors.keyNotUsable(key.kid(), op);
    }
    return p;
  }

  private static void sameProvider(Resolved a, Resolved b, String op) {
    if (!a.provider().name().equals(b.provider().name())) {
      throw CryptoErrors.keyNotUsable(b.kid(), op + " (keys in different HSMs)");
    }
  }
}

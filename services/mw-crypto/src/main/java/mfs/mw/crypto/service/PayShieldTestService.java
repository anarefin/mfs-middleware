package mfs.mw.crypto.service;

import java.util.function.Predicate;
import mfs.mw.crypto.client.Capability;
import mfs.mw.crypto.config.TestApiProperties;
import mfs.mw.crypto.dto.PayShieldTestKey;
import mfs.mw.crypto.dto.PvvRequest;
import mfs.mw.crypto.dto.PvvView;
import mfs.mw.crypto.security.TestApiCaller;
import mfs.mw.crypto.service.HsmKeys.Resolved;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * The dev-only payShield test API (ticket 76, {@link TestApiProperties}): the LMK blob of a PIN key
 * (TPK, ZPK and their AES kinds; never a PVK) and PVV generate / verify through {@link
 * PinBlockService} as the test-only client, so the caller policy applies unchanged.
 */
@Service
@ConditionalOnProperty(name = "mw.crypto.test-api.enabled", havingValue = "true")
public class PayShieldTestService {

  private static final Predicate<KeyPurpose> PIN_KEY = p -> p.capability() == Capability.PIN_BLOCK;

  private final HsmKeys keys;
  private final PinBlockService pinBlocks;
  private final TestApiProperties props;

  public PayShieldTestService(HsmKeys keys, PinBlockService pinBlocks, TestApiProperties props) {
    this.keys = keys;
    this.pinBlocks = pinBlocks;
    this.props = props;
  }

  public PayShieldTestKey pinKey(String purpose) {
    Resolved key = keys.activeByPurpose(purpose, PIN_KEY, "test-pin-key");
    return new PayShieldTestKey(key.key().keyId(), key.kid(), key.purpose().name(), key.label());
  }

  public PvvView generatePvv(PvvRequest r) {
    return TestApiCaller.as(props.client(), () -> pinBlocks.generatePvv(r));
  }

  public PvvView verifyPvv(PvvRequest r) {
    return TestApiCaller.as(props.client(), () -> pinBlocks.verifyPvv(r));
  }
}

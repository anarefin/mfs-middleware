package mfs.mw.seeder.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import mfs.mw.seeder.client.AuthSeeds;
import mfs.mw.seeder.config.SeederProperties;
import mfs.mw.seeder.config.SeederProperties.WalletSpec;
import mfs.mw.seeder.dto.PinKeyView;
import mfs.mw.seeder.dto.SeedManifest;
import mfs.mw.seeder.dto.SeedPinsRequest;
import mfs.mw.seeder.dto.SeedPinsResult;
import mfs.mw.seeder.exception.RemoteCallException;
import mfs.mw.seeder.util.PinCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * seed-app-pins (ticket 69): the app wallets log in at mw-auth with MSISDN + PIN, so each gets the
 * dev app PIN ({@code mw.seeder.app-pins.pin}, {@code MW_SEED_APP_PIN}, a fixed fake value): the
 * seeded CUSTOMER, AGENT, MERCHANT and DISTRIBUTOR (ticket 94) wallets plus {@code extra-wallet-ids} (the realm imports' app
 * users the Newman suite logs in as). The PIN is encrypted as an app does, under mw-auth's PIN
 * transport key, and set through mw-auth's dev-only seed API, which leaves a wallet that has a PIN
 * alone. mw-auth learns about a wallet from the core's wallet events, asynchronously: a wallet it has
 * not projected yet makes the step retry (503). The manifest names the PIN's variable, never the PIN.
 */
@Service
@Order(4)
public class SeedAppPinsStep implements SeedStep {

  private static final Logger log = LoggerFactory.getLogger(SeedAppPinsStep.class);
  private static final Set<String> APP_TYPES = Set.of("CUSTOMER", "AGENT", "MERCHANT", "DISTRIBUTOR", "DSO", "PARTNER");

  private final AuthSeeds auth;
  private final SeederProperties props;

  public SeedAppPinsStep(AuthSeeds auth, SeederProperties props) {
    this.auth = auth;
    this.props = props;
  }

  @Override
  public String id() {
    return "seed-app-pins";
  }

  @Override
  public StepResult run(SeedContext ctx) {
    SeederProperties.AppPins cfg = props.appPins();
    if (cfg == null || cfg.pin() == null || cfg.pin().isBlank()) {
      log.atWarn().addKeyValue("event", "seed.app_pins.skipped").log("No app PIN configured: app logins will fail");
      return StepResult.unchanged(0);
    }
    List<String> wallets = new ArrayList<>();
    for (WalletSpec w : props.wallets()) {
      if (APP_TYPES.contains(w.type())) {
        wallets.add(w.walletId());
      }
    }
    for (String extra : cfg.extraWalletIds()) {
      if (!wallets.contains(extra)) {
        wallets.add(extra);
      }
    }
    PinKeyView key = auth.pinKey();
    List<SeedPinsRequest.Pin> pins = new ArrayList<>();
    for (String w : wallets) {
      pins.add(new SeedPinsRequest.Pin(w,
          new SeedPinsRequest.EncryptedPin(key.keyId(), PinCipher.encrypt(cfg.pin(), key.publicKeyPem()))));
    }
    SeedPinsResult result = auth.seedPins(new SeedPinsRequest(pins));
    StepResult r = StepResult.unchanged(0);
    int unknown = 0;
    for (SeedPinsResult.Outcome o : result.results()) {
      switch (o.outcome()) {
        case "CREATED" -> r = r.plus(StepResult.created(1));
        case "UNCHANGED" -> r = r.plus(StepResult.unchanged(1));
        case "UNKNOWN_WALLET" -> unknown++;
        default -> throw new IllegalStateException("mw-auth refused the dev app PIN (" + o.outcome() + ")");
      }
    }
    if (unknown > 0) {
      throw new RemoteCallException(503, unknown + " app wallet(s) not known to mw-auth yet (wallet events pending)");
    }
    ctx.setAppPins(new SeedManifest.AppPins(cfg.pinEnv(), List.copyOf(wallets)));
    return r;
  }
}

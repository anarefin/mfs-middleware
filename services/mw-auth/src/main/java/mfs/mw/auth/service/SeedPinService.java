package mfs.mw.auth.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import mfs.mw.auth.dto.PinHmac;
import mfs.mw.auth.dto.SeedPinsRequest;
import mfs.mw.auth.dto.SeedPinsResult;
import mfs.mw.auth.entity.AuthWallet;
import mfs.mw.auth.exception.MwException;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.repository.CredentialRepository;
import mfs.mw.auth.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Dev only (ticket 69, {@code mw.auth.seed-api.enabled}): the seeder's first PINs. Each PIN arrives
 * encrypted under the PIN transport key, as from an app; mw-crypto derives the HMAC. Idempotent: a
 * wallet with a PIN is UNCHANGED (no mw-crypto call). {@code appRole PARTNER} marks that organisation
 * wallet for the partner app login (the core type stays ORG); a mark on any other type is ignored,
 * and a wallet that already has a PIN is still marked.
 */
@Service
@ConditionalOnProperty(name = "mw.auth.seed-api.enabled", havingValue = "true")
public class SeedPinService {

  private static final Logger log = LoggerFactory.getLogger(SeedPinService.class);

  private final WalletRepository wallets;
  private final CredentialRepository credentials;
  private final PinService pins;
  private final Clock clock;

  public SeedPinService(WalletRepository wallets, CredentialRepository credentials, PinService pins, Clock clock) {
    this.wallets = wallets;
    this.credentials = credentials;
    this.pins = pins;
    this.clock = clock;
  }

  public SeedPinsResult seed(SeedPinsRequest request) {
    List<SeedPinsResult.Outcome> out = new ArrayList<>();
    for (SeedPinsRequest.Pin p : request.pins()) {
      out.add(new SeedPinsResult.Outcome(p.walletId(), seed(p)));
    }
    log.atInfo().addKeyValue("event", "auth.seed.pins")
        .log("Seed PINs: {}", out.stream().map(SeedPinsResult.Outcome::outcome).toList());
    return new SeedPinsResult(out);
  }

  private String seed(SeedPinsRequest.Pin p) {
    Optional<AuthWallet> wallet = wallets.find(p.walletId());
    if (wallet.isEmpty()) {
      return "UNKNOWN_WALLET";
    }
    if ("PARTNER".equals(p.appRole()) && "ORG".equals(wallet.get().type())) {
      wallets.markPartnerApp(p.walletId());
    }
    if (credentials.find(p.walletId()).isPresent()) {
      return "UNCHANGED";
    }
    PinHmac hmac;
    try {
      hmac = pins.derive(p.walletId(), p.encryptedPin(), null);
    } catch (MwException e) {
      if (ErrorCodes.AUTH_WEAK_PIN.equals(e.code())) {
        return "WEAK";
      }
      throw e;
    }
    return credentials.insertIfAbsent(p.walletId(), hmac, clock.instant()) ? "CREATED" : "UNCHANGED";
  }
}

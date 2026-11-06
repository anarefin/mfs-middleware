package mfs.mw.core.mock.controller;

import jakarta.validation.Valid;
import mfs.mw.core.mock.dto.WalletBalance;
import mfs.mw.core.mock.dto.WalletSeedRequest;
import mfs.mw.core.mock.service.WalletSeedService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only seed API (ticket 57, {@code contracts/api/mw-core-mock/core-mock-seed.v1.openapi.yaml}):
 * mw-seeder creates the seed personas' wallets. Only with {@code mw.coremock.seed-api.enabled=true}
 * ({@code MW_COREMOCK_SEED_API_ENABLED}, set by the dev stack); 201 created, 200 already there.
 */
@RestController
@RequestMapping("/core-mock/v1/wallets")
@ConditionalOnProperty(name = "mw.coremock.seed-api.enabled", havingValue = "true")
public class WalletSeedController {

  private final WalletSeedService seeds;

  public WalletSeedController(WalletSeedService seeds) {
    this.seeds = seeds;
  }

  @PutMapping("/{walletId}")
  ResponseEntity<WalletBalance> seed(@PathVariable String walletId, @Valid @RequestBody WalletSeedRequest body) {
    WalletSeedService.Seeded s = seeds.seed(walletId, body.type(), body.openingBalanceMinor());
    return ResponseEntity.status(s.created() ? HttpStatus.CREATED : HttpStatus.OK).body(s.wallet());
  }
}

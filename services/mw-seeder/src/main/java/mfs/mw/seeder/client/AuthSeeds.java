package mfs.mw.seeder.client;

import mfs.mw.seeder.dto.PinKeyView;
import mfs.mw.seeder.dto.SeedPinsRequest;
import mfs.mw.seeder.dto.SeedPinsResult;

/**
 * mw-auth for the seeder (ticket 69; implemented by {@link AuthSeedClient}): the PIN transport key
 * ({@code GET /auth/v1/pin-key}, public) and the dev-only first PINs ({@code POST
 * /internal/v1/seed/pins}, only with {@code MW_AUTH_SEED_API_ENABLED=true}).
 */
public interface AuthSeeds {

  PinKeyView pinKey();

  SeedPinsResult seedPins(SeedPinsRequest request);
}

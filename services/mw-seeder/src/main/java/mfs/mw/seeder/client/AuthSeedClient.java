package mfs.mw.seeder.client;

import java.net.URI;
import java.util.Map;
import mfs.mw.seeder.config.SeederProperties;
import mfs.mw.seeder.dto.PinKeyView;
import mfs.mw.seeder.dto.SeedPinsRequest;
import mfs.mw.seeder.dto.SeedPinsResult;
import org.springframework.stereotype.Component;

/**
 * mw-auth's public port ({@code http://mw-auth:8080}, plain HTTP inside the {@code app} network):
 * {@code GET /auth/v1/pin-key} ({@code contracts/api/mw-auth/auth.v1.openapi.yaml}) and the dev-only
 * {@code POST /internal/v1/seed/pins} ({@code auth-internal.v1.openapi.yaml}; no token, it exists only
 * when the dev stack switches it on, else a 401).
 */
@Component
public class AuthSeedClient implements AuthSeeds {

  private static final String KEY = "GET /auth/v1/pin-key";
  private static final String SEED = "POST /internal/v1/seed/pins";

  private final HttpJson http;
  private final URI base;

  public AuthSeedClient(HttpJson http, SeederProperties props) {
    this.http = http;
    this.base = props.endpoints().auth();
  }

  @Override
  public PinKeyView pinKey() {
    HttpJson.Response r = HttpJson.expect(KEY, http.get(KEY, base.resolve("/auth/v1/pin-key"), Map.of()), 200);
    return http.read(KEY, r, PinKeyView.class);
  }

  @Override
  public SeedPinsResult seedPins(SeedPinsRequest request) {
    HttpJson.Response r = HttpJson.expect(SEED,
        http.sendJson(SEED, "POST", base.resolve("/internal/v1/seed/pins"), Map.of(), request), 200);
    return http.read(SEED, r, SeedPinsResult.class);
  }
}

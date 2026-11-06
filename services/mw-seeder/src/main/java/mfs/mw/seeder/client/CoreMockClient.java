package mfs.mw.seeder.client;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import mfs.mw.seeder.config.SeederProperties;
import mfs.mw.seeder.dto.WalletSeedRequest;
import mfs.mw.seeder.dto.WalletView;
import org.springframework.stereotype.Component;

/**
 * mw-core-mock's wallet API ({@code contracts/api/mw-core-mock/core-mock-seed.v1.openapi.yaml}):
 * looks the wallet up ({@code GET /core-mock/v1/wallets/{walletId}}, unknown = 400 MW-VAL-003) and
 * only when it is missing creates it with the test-only {@code PUT} (201 created, 200 when it
 * appeared meanwhile; never changed). The PUT is only on when core-mock runs with {@code
 * MW_COREMOCK_SEED_API_ENABLED=true} (the dev stack); otherwise it is a 405.
 */
@Component
public class CoreMockClient implements CoreAccounts {

  private static final String GET = "GET /core-mock/v1/wallets/{walletId}";
  private static final String PUT = "PUT /core-mock/v1/wallets/{walletId}";

  private final HttpJson http;
  private final URI base;

  public CoreMockClient(HttpJson http, SeederProperties props) {
    this.http = http;
    this.base = props.endpoints().coreMock();
  }

  @Override
  public Seeded ensureWallet(String walletId, String type, long openingBalanceMinor) {
    URI u = base.resolve("/core-mock/v1/wallets/" + URLEncoder.encode(walletId, StandardCharsets.UTF_8));
    HttpJson.Response found = HttpJson.expect(GET, http.get(GET, u, Map.of()), 200, 400);
    if (found.status() == 200) {
      return new Seeded(false, http.read(GET, found, WalletView.class));
    }
    HttpJson.Response r =
        HttpJson.expect(PUT, http.sendJson(PUT, "PUT", u, Map.of(), new WalletSeedRequest(type, openingBalanceMinor)), 200, 201);
    return new Seeded(r.status() == 201, http.read(PUT, r, WalletView.class));
  }
}

package mfs.mw.admin.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import mfs.mw.admin.api.dto.ChannelClientSpec;
import mfs.mw.admin.api.dto.ChannelClientSpec.AuthMethod;
import mfs.mw.admin.api.dto.ChannelClientSpec.ClientType;
import mfs.mw.admin.api.dto.ErrorDetail;
import mfs.mw.admin.api.entity.ChannelClient;
import org.junit.jupiter.api.Test;

/** An OAUTH2_CC client cannot take a route another auth method already uses (ticket 100b). */
class Oauth2RouteGuardTest {

  static final Instant AT = Instant.parse("2026-11-20T08:00:00Z");

  @Test
  void anOauth2ClientCannotUseTheHmacCallbackRouteAndTwoOauthClientsCanShareOne() {
    ChannelClient billagg = client("billagg", AuthMethod.HMAC, "partner-billagg-callback");
    ChannelClient bank = client("c2wbank", AuthMethod.HMAC, "partner-c2wbank-callback");
    List<ErrorDetail> problems = new ArrayList<>();

    ChannelClientService.rejectAuthRouteClash(
        "partner-sim",
        spec(AuthMethod.OAUTH2_CC, "partner-billagg-callback"),
        List.of(billagg, bank),
        problems);

    assertThat(problems).extracting(ErrorDetail::field).containsExactly("client.allowedRoutes");
    assertThat(problems.getFirst().issue()).contains("partner-billagg-callback", "billagg", "HMAC");

    problems.clear();
    ChannelClientService.rejectAuthRouteClash(
        "partner-other",
        spec(AuthMethod.OAUTH2_CC, "test-oauth2-cc"),
        List.of(billagg, client("partner-sim", AuthMethod.OAUTH2_CC, "test-oauth2-cc")),
        problems);
    assertThat(problems).isEmpty();
  }

  private static ChannelClient client(String id, AuthMethod auth, String route) {
    return new ChannelClient(id, spec(auth, route), 1, AT, "ops-maker01", "ops-checker01");
  }

  private static ChannelClientSpec spec(AuthMethod auth, String route) {
    return new ChannelClientSpec(ClientType.PARTNER, auth, List.of(route), List.of(), null, null, true);
  }
}

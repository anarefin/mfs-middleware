package mfs.mw.crypto.security;

import java.util.function.Supplier;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Runs a call as the dev-only test client (ticket 76): the security context holds a synthetic
 * principal whose {@code azp} is that client for the length of the call, so {@link CallerPolicy}
 * decides exactly as for a real caller (the dev profile binds the client to TPK and PVK only). Any
 * token that came with the request is set aside, never used.
 */
public final class TestApiCaller {

  private TestApiCaller() {}

  public static <T> T as(String client, Supplier<T> call) {
    SecurityContext previous = SecurityContextHolder.getContext();
    SecurityContext ctx = SecurityContextHolder.createEmptyContext();
    Jwt jwt = Jwt.withTokenValue("test-api").header("alg", "none").claim("azp", client).subject(client).build();
    ctx.setAuthentication(new JwtAuthenticationToken(jwt));
    SecurityContextHolder.setContext(ctx);
    try {
      return call.get();
    } finally {
      SecurityContextHolder.setContext(previous);
    }
  }
}

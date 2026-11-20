package mfs.mw.core.mock.config;

import java.util.List;
import mfs.mw.core.mock.exception.ErrorCodes;
import mfs.mw.core.mock.exception.MwException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * The core wallet API {@code /core/v1/**} (ADR D31, ticket 93) needs a Keycloak client-credentials
 * JWT (spec 18.2; {@link InternalAuthProperties}) of an allowed client ({@code azp}). Everything else
 * stays open inside the app network as before: the test/admin API {@code /core-mock/v1/**} (dev-only
 * APISIX route, mw-seeder) and the actuator probes. Failures are the 5.3 envelope from the global
 * handler: no or bad token (signature, expiry, issuer, audience) 401 {@code MW-AUTH-001}, a client
 * that may not call 403 {@code MW-AUTH-002}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSecurity
@EnableConfigurationProperties(InternalAuthProperties.class)
public class SecurityConfig {

  @Bean
  SecurityFilterChain coreWalletApiSecurity(
      HttpSecurity http,
      InternalAuthProperties props,
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors)
      throws Exception {
    AuthenticationEntryPoint unauthenticated =
        (req, res, e) ->
            errors.resolveException(
                req, res, null, new MwException(ErrorCodes.AUTH_UNAUTHENTICATED, "Authentication required"));
    AccessDeniedHandler denied =
        (req, res, e) ->
            errors.resolveException(
                req, res, null,
                new MwException(ErrorCodes.AUTH_FORBIDDEN, "Not allowed").withStatus(HttpStatus.FORBIDDEN));
    http.csrf(c -> c.disable())
        .headers(h -> h.disable())
        .httpBasic(b -> b.disable())
        .formLogin(f -> f.disable())
        .requestCache(r -> r.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(HttpMethod.POST, "/core/v1/accounts/*/unlock")
                    .access(caller(props, UNLOCK_CALLER))
                    .requestMatchers(HttpMethod.POST, "/core/v1/customers/*/verify-factors")
                    .access(caller(props, FACTORS_CALLER))
                    .requestMatchers(HttpMethod.GET, "/core/v1/reports/emoney-total")
                    .access(reportCaller(props))
                    .requestMatchers(HttpMethod.GET, "/core/v1/balance/*")
                    .access(balanceCaller(props))
                    .requestMatchers("/core/**")
                    .access(
                        (auth, ctx) ->
                            new AuthorizationDecision(
                                auth.get() != null
                                    && auth.get().getPrincipal() instanceof Jwt jwt
                                    && props.allowedClients().contains(jwt.getClaimAsString("azp"))))
                    .anyRequest()
                    .permitAll())
        .oauth2ResourceServer(
            o -> o.jwt(j -> {}).authenticationEntryPoint(unauthenticated).accessDeniedHandler(denied))
        .exceptionHandling(x -> x.authenticationEntryPoint(unauthenticated).accessDeniedHandler(denied));
    return http.build();
  }

  /** The one client that may unlock an account: the ops console's maker-checker (ticket 95b). */
  static final String UNLOCK_CALLER = "mw-admin-api";
  /** The one client that may check a customer's KYC factors: the PIN reset (ticket 95b). */
  static final String FACTORS_CALLER = "mw-auth";

  /** An allowed client ({@code azp}) that is also the operation's one caller. */
  private static AuthorizationManager<RequestAuthorizationContext> caller(InternalAuthProperties props, String client) {
    return (auth, ctx) -> new AuthorizationDecision(
        auth.get() != null
            && auth.get().getPrincipal() instanceof Jwt jwt
            && client.equals(jwt.getClaimAsString("azp"))
            && props.allowedClients().contains(client));
  }

  /**
   * A report reader (ticket 127c): an {@code azp} of {@code report-clients} (mw-recon), a list of its own, so a
   * report reader is never an allowed client of the wallet operations.
   */
  private static AuthorizationManager<RequestAuthorizationContext> reportCaller(InternalAuthProperties props) {
    return (auth, ctx) -> new AuthorizationDecision(
        auth.get() != null
            && auth.get().getPrincipal() instanceof Jwt jwt
            && props.reportClients().contains(jwt.getClaimAsString("azp")));
  }

  /**
   * A balance reader: an allowed client, or an {@code azp} of {@code balance-clients} (ticket 129c:
   * mw-portal-api), which may read balances and nothing else ({@code CoreWalletService} limits it
   * to ORGANISATION wallets).
   */
  private static AuthorizationManager<RequestAuthorizationContext> balanceCaller(InternalAuthProperties props) {
    return (auth, ctx) -> new AuthorizationDecision(
        auth.get() != null
            && auth.get().getPrincipal() instanceof Jwt jwt
            && (props.allowedClients().contains(jwt.getClaimAsString("azp"))
                || props.balanceClients().contains(jwt.getClaimAsString("azp"))));
  }

  /** Signature from the realm's JWKS (fetched on first use), expiry, exact issuer and audience. */
  @Bean
  JwtDecoder jwtDecoder(InternalAuthProperties props) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(props.jwkSetUri()).build();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(props.issuer()), audience(props.audience())));
    return decoder;
  }

  /** Token validator: {@code aud} must contain {@code audience}. */
  static OAuth2TokenValidator<Jwt> audience(String audience) {
    OAuth2Error wrong = new OAuth2Error("invalid_token", "audience does not contain " + audience, null);
    return jwt -> {
      List<String> aud = jwt.getAudience();
      return aud != null && aud.contains(audience)
          ? OAuth2TokenValidatorResult.success()
          : OAuth2TokenValidatorResult.failure(wrong);
    };
  }
}

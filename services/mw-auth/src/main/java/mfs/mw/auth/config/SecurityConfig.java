package mfs.mw.auth.config;

import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import mfs.mw.auth.security.InternalCallerPolicy;
import mfs.mw.auth.security.OwnTokenDecoder;
import mfs.mw.auth.service.JwksService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Two filter chains (spec 18.2, 18.7, D24). {@code /internal/**} needs a Keycloak client-credentials
 * JWT of an allowed client ({@link InternalAuthProperties}, {@link InternalCallerPolicy}), on the
 * internal port only ({@link PublicConnectorConfig}) and, with {@code mw.tls.enabled}, a client
 * certificate whose CN equals the token's {@code azp} (the connector itself needs a certificate of
 * the Vault intermediate: {@code server.ssl.client-auth: need}). {@code /auth/v1/**} (ticket 69) is
 * open (OTP, binding, login, refresh, the key documents: they prove the wallet themselves with the
 * device signature, OTP and PIN), except logout and the first PIN, which take mw-auth's own access
 * token ({@link OwnTokenDecoder}: RS256 against the JWT_SIGN keys, exact issuer and audience; a
 * decoder of its own, not a bean, so the internal chain keeps the services realm decoder). Dev only,
 * with {@code mw.auth.seed-api.enabled}: {@code /internal/v1/seed/**} and {@code /test/v1/accounts/**}
 * without a token (chain 0).
 * Everything else is open (actuator is on the management port). Failures are the 5.3 envelope: no
 * or bad token 401 {@code MW-AUTH-001}, a caller that may not ask 403 {@code MW-AUTH-002}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSecurity
@EnableConfigurationProperties(InternalAuthProperties.class)
public class SecurityConfig {

  /** The public endpoints that take mw-auth's own access token. */
  static final java.util.Set<String> TOKEN_PATHS = java.util.Set.of("/auth/v1/sessions/logout", "/auth/v1/pin");

  /**
   * Dev only: the seeder's first PINs ({@code controller/SeedController}) and the test console's
   * account actions ({@code controller/TestAccountController}, {@code /test/v1/accounts/**}), no token.
   * Off, neither path is open: the seed path is the internal chain's (401) and no controller answers
   * {@code /test/**} (404).
   */
  @Bean
  @Order(0)
  @ConditionalOnProperty(name = "mw.auth.seed-api.enabled", havingValue = "true")
  SecurityFilterChain seedApiSecurity(HttpSecurity http) throws Exception {
    stateless(http).securityMatcher("/internal/v1/seed/**", "/test/v1/accounts/**")
        .authorizeHttpRequests(a -> a.anyRequest().permitAll());
    return http.build();
  }

  @Bean
  @Order(1)
  SecurityFilterChain internalApiSecurity(
      HttpSecurity http,
      InternalAuthProperties props,
      PublicConnectorConfig.PublicConnector publicConnector,
      @Value("${mw.tls.enabled:false}") boolean mtls,
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors)
      throws Exception {
    InternalCallerPolicy policy = new InternalCallerPolicy(props.allowedClients(), mtls, publicConnector::received);
    AuthenticationEntryPoint unauthenticated =
        (req, res, e) ->
            errors.resolveException(
                req, res, null, new MwException(ErrorCodes.AUTH_UNAUTHENTICATED, "Authentication required"));
    AccessDeniedHandler denied =
        (req, res, e) ->
            errors.resolveException(
                req, res, null,
                new MwException(ErrorCodes.AUTH_FORBIDDEN, "Not allowed").withStatus(HttpStatus.FORBIDDEN));
    stateless(http)
        .securityMatcher("/internal/**")
        .authorizeHttpRequests(
            a ->
                a.anyRequest()
                    .access(
                        (auth, ctx) ->
                            new AuthorizationDecision(
                                auth.get() != null
                                    && auth.get().getPrincipal() instanceof Jwt jwt
                                    && policy.allows(jwt, ctx.getRequest()))))
        .oauth2ResourceServer(
            o -> o.jwt(j -> {}).authenticationEntryPoint(unauthenticated).accessDeniedHandler(denied))
        .exceptionHandling(x -> x.authenticationEntryPoint(unauthenticated).accessDeniedHandler(denied));
    return http.build();
  }

  /** The public API: open, but logout and the first PIN need mw-auth's own access token. */
  @Bean
  @Order(2)
  SecurityFilterChain appApiSecurity(
      HttpSecurity http,
      JwksService jwks,
      TokenProperties tokens,
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
    JwtDecoder decoder = OwnTokenDecoder.create(jwks, tokens);
    DefaultBearerTokenResolver bearer = new DefaultBearerTokenResolver();
    stateless(http)
        .securityMatcher("/auth/v1/**")
        .authorizeHttpRequests(a -> a.requestMatchers(TOKEN_PATHS.toArray(String[]::new)).authenticated()
            .anyRequest().permitAll())
        .oauth2ResourceServer(
            o -> o.jwt(j -> j.decoder(decoder))
                // only the token endpoints read a bearer token: a stale one sent to the login is ignored
                .bearerTokenResolver(req -> TOKEN_PATHS.contains(req.getRequestURI()) ? bearer.resolve(req) : null)
                .authenticationEntryPoint(unauthenticated)
                .accessDeniedHandler(denied))
        .exceptionHandling(x -> x.authenticationEntryPoint(unauthenticated).accessDeniedHandler(denied));
    return http.build();
  }

  /** Everything else: open (actuator probes; unknown paths are answered 404). */
  @Bean
  @Order(3)
  SecurityFilterChain otherSecurity(HttpSecurity http) throws Exception {
    stateless(http).authorizeHttpRequests(a -> a.anyRequest().permitAll());
    return http.build();
  }

  private static HttpSecurity stateless(HttpSecurity http) throws Exception {
    return http.csrf(c -> c.disable())
        .headers(h -> h.disable())
        .httpBasic(b -> b.disable())
        .formLogin(f -> f.disable())
        .requestCache(r -> r.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
  }

  /** Signature from the realm's JWKS (fetched on first use), expiry, exact issuer and audience. */
  @Bean
  JwtDecoder jwtDecoder(InternalAuthProperties props) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(props.jwkSetUri()).build();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(props.issuer()),
            InternalCallerPolicy.audience(props.audience())));
    return decoder;
  }
}

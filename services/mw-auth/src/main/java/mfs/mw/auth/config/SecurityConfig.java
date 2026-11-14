package mfs.mw.auth.config;

import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import mfs.mw.auth.security.InternalCallerPolicy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Two filter chains (spec 18.2, 18.7, D24). {@code /internal/**} needs a Keycloak client-credentials
 * JWT of an allowed client ({@link InternalAuthProperties}, {@link InternalCallerPolicy}), on the
 * internal port only ({@link PublicConnectorConfig}) and, with {@code mw.tls.enabled}, a client
 * certificate whose CN equals the token's {@code azp} (the connector itself needs a certificate of
 * the OpenBao intermediate: {@code server.ssl.client-auth: need}). Everything else is the second
 * chain, open for now: {@code /auth/v1/**} gets its own token rules in tickets 68-69 (mw-auth's
 * tokens, not Keycloak's, so the resource server never sees them); actuator is on the management
 * port. Failures are the 5.3 envelope: no or bad token 401 {@code MW-AUTH-001}, a caller that may
 * not ask 403 {@code MW-AUTH-002}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSecurity
@EnableConfigurationProperties(InternalAuthProperties.class)
public class SecurityConfig {

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

  /** Everything outside {@code /internal/**}: open until tickets 68-69 add the public rules. */
  @Bean
  @Order(2)
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

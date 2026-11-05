package mfs.mw.orchestrator.config;

import mfs.mw.orchestrator.exception.ErrorCodes;
import mfs.mw.orchestrator.exception.MwException;
import mfs.mw.orchestrator.security.InternalCallerPolicy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * {@code /internal/v1/**} needs a Keycloak client-credentials JWT (spec 18.2, M3 gate decision;
 * {@link InternalAuthProperties}, {@link InternalCallerPolicy}). Everything else (actuator probes)
 * stays open inside the app network. Failures are the 5.3 envelope from the global handler: no or
 * bad token (signature, expiry, issuer, audience) 401 {@code MW-AUTH-001}, a caller that may not
 * ask 403 {@code MW-AUTH-002}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSecurity
@EnableConfigurationProperties(InternalAuthProperties.class)
public class SecurityConfig {

  @Bean
  SecurityFilterChain internalApiSecurity(
      HttpSecurity http,
      InternalAuthProperties props,
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors)
      throws Exception {
    InternalCallerPolicy policy = new InternalCallerPolicy(props.allowedClients(), props.orgScopedClients(), props.revealClients());
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
                a.requestMatchers("/internal/**")
                    .access(
                        (auth, ctx) ->
                            new AuthorizationDecision(
                                auth.get() != null
                                    && auth.get().getPrincipal() instanceof Jwt jwt
                                    && policy.allows(jwt, ctx.getRequest())))
                    .anyRequest()
                    .permitAll())
        .oauth2ResourceServer(
            o -> o.jwt(j -> {}).authenticationEntryPoint(unauthenticated).accessDeniedHandler(denied))
        .exceptionHandling(x -> x.authenticationEntryPoint(unauthenticated).accessDeniedHandler(denied));
    return http.build();
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

package mfs.mw.compliance.config;

import java.util.List;
import mfs.mw.compliance.exception.ErrorCodes;
import mfs.mw.compliance.exception.MwException;
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
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * mw-compliance's internal API {@code /internal/**} (ticket 127b security review; copied from mw-core-mock) needs a
 * Keycloak realm {@code services} client-credentials JWT ({@link InternalAuthProperties}: issuer, {@code aud}
 * containing mw-compliance) of an allowed client ({@code azp}: mw-admin-api, the console's only way in). The caller is
 * then authenticated, so {@code X-Mw-Operator-Id} is mw-admin-api's assertion of the operator. The actuator probes
 * and the dev-only {@code /test/v1/**} API stay open. Failures are the 5.3 envelope: no or bad token 401 {@code
 * MW-AUTH-001}, a client that may not call 403 {@code MW-AUTH-002}.
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
                                    && props.allowedClients().contains(jwt.getClaimAsString("azp"))))
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

package mfs.mw.app.gateway.config;

import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.security.RealmIssuerValidator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Bearer JWT from Keycloak realm {@code customers} on {@code /app/**} (spec 9.1, 18.2);
 * everything else (actuator probes, unknown paths) stays open inside the app network, which only
 * APISIX and Compose healthchecks reach. Auth failures are the 5.3 envelope, built by the global
 * handler. Security headers come from the chassis filter, so Spring Security's are off.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSecurity
@EnableConfigurationProperties(AppGatewayProperties.class)
public class SecurityConfig {

  @Bean
  SecurityFilterChain appSecurity(
      HttpSecurity http,
      @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors)
      throws Exception {
    AuthenticationEntryPoint unauthenticated =
        (req, res, e) ->
            errors.resolveException(
                req, res, null,
                new MwException(ErrorCodes.AUTH_UNAUTHENTICATED, "Authentication required"));
    AccessDeniedHandler denied =
        (req, res, e) ->
            errors.resolveException(
                req, res, null,
                new MwException(ErrorCodes.AUTH_FORBIDDEN, "Not allowed")
                    .withStatus(HttpStatus.FORBIDDEN));
    http.csrf(c -> c.disable())
        .headers(h -> h.disable())
        .httpBasic(b -> b.disable())
        .formLogin(f -> f.disable())
        .requestCache(r -> r.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a -> a.requestMatchers("/app/**").authenticated().anyRequest().permitAll())
        .oauth2ResourceServer(
            o ->
                o.jwt(j -> {})
                    .authenticationEntryPoint(unauthenticated)
                    .accessDeniedHandler(denied))
        .exceptionHandling(x -> x.authenticationEntryPoint(unauthenticated).accessDeniedHandler(denied));
    return http.build();
  }

  /** Signature from the realm's JWKS (fetched on first use), expiry, and the realm in {@code iss}. */
  @Bean
  JwtDecoder jwtDecoder(AppGatewayProperties props) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(props.jwt().jwkSetUri()).build();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefault(), new RealmIssuerValidator(props.jwt().realmPath())));
    return decoder;
  }
}

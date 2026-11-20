package mfs.mw.app.gateway.config;

import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import java.util.List;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Bearer JWT issued by mw-auth on {@code /app/**} (spec 9.1, 18.7, D21; ticket 69, until then the
 * Keycloak realm {@code customers});
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
            a -> a.requestMatchers(HttpMethod.GET, "/app/ws/v1/*.wsdl").permitAll()
                .requestMatchers("/app/**").authenticated()
                .anyRequest().permitAll())
        .oauth2ResourceServer(
            o ->
                o.jwt(j -> {})
                    .authenticationEntryPoint(unauthenticated)
                    .accessDeniedHandler(denied))
        .exceptionHandling(x -> x.authenticationEntryPoint(unauthenticated).accessDeniedHandler(denied));
    return http.build();
  }

  @Bean
  JwtDecoder jwtDecoder(AppGatewayProperties props) {
    return appTokenDecoder(props.jwt());
  }

  /**
   * An mw-auth access token: RS256 only (the builder's default) by a key of mw-auth's JWK Set
   * (fetched on first use, again on an unknown kid), header {@code typ at+jwt}, expiry, the exact
   * issuer and the audience. Not {@code JwtValidators.createDefault*}: since Spring Security 7 they
   * accept {@code typ JWT} only.
   */
  public static JwtDecoder appTokenDecoder(AppGatewayProperties.Jwt jwt) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwt.jwkSetUri())
        .jwtProcessorCustomizer(p -> p.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(new JOSEObjectType("at+jwt"))))
        .build();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(),
            new JwtIssuerValidator(jwt.issuer()),
            new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(jwt.audience()))));
    return decoder;
  }
}

package mfs.mw.admin.api.config;

import java.util.Set;
import java.util.function.Predicate;
import mfs.mw.admin.api.exception.ErrorCodes;
import mfs.mw.admin.api.exception.MwException;
import mfs.mw.admin.api.security.AdminRole;
import mfs.mw.admin.api.security.OperatorResolver;
import mfs.mw.admin.api.security.RealmIssuerValidator;
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
 * Bearer JWT of Keycloak realm {@code ops} on {@code /admin/**} (spec 15.1, 18.2), checked again
 * after APISIX; MFA is the realm's (password + TOTP in the browser flow, TOTP enrolment required of
 * every user). Roles of spec 15.4 per endpoint ({@link AdminRole}): the transaction reads need a
 * {@link AdminRole#READ_TRANSACTIONS} role; any method that could change something needs a role
 * that is not read-only (viewer and auditor never write); any other admin path needs some 15.4
 * role. Everything else (actuator probes) stays open inside the app network. Auth failures are the
 * 5.3 envelope, built by the global handler.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSecurity
@EnableConfigurationProperties(AdminApiProperties.class)
public class SecurityConfig {

  private static final Set<HttpMethod> SAFE = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);

  @Bean
  SecurityFilterChain adminSecurity(
      HttpSecurity http, @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) throws Exception {
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
                a.requestMatchers(r -> r.getRequestURI().startsWith("/admin/") && !SAFE.contains(HttpMethod.valueOf(r.getMethod())))
                    .access(roles(AdminRole::mayWrite))
                    .requestMatchers(
                        HttpMethod.GET, "/admin/v1/transactions", "/admin/v1/transactions/*",
                        "/admin/v1/change-requests", "/admin/v1/change-requests/*")
                    .access(roles(r -> r.stream().anyMatch(AdminRole.READ_TRANSACTIONS::contains)))
                    // maker-checker on transactions (ticket 37); the service checks again
                    .requestMatchers(
                        HttpMethod.POST, "/admin/v1/transactions/*/change-requests", "/admin/v1/wallets/*/change-requests")
                    .access(roles(r -> r.stream().anyMatch(AdminRole.TXN_MAKER::contains)))
                    .requestMatchers(
                        HttpMethod.POST, "/admin/v1/change-requests/*/approve", "/admin/v1/change-requests/*/reject")
                    .access(roles(r -> r.stream().anyMatch(AdminRole.TXN_CHECKER::contains)))
                    .requestMatchers("/admin/**")
                    .access(roles(r -> !r.isEmpty()))
                    .anyRequest()
                    .permitAll())
        .oauth2ResourceServer(
            o -> o.jwt(j -> {}).authenticationEntryPoint(unauthenticated).accessDeniedHandler(denied))
        .exceptionHandling(x -> x.authenticationEntryPoint(unauthenticated).accessDeniedHandler(denied));
    return http.build();
  }

  /** Allows a realm {@code ops} token whose spec 15.4 roles pass {@code rule}. */
  private static AuthorizationManager<RequestAuthorizationContext> roles(Predicate<Set<AdminRole>> rule) {
    return (auth, ctx) ->
        new AuthorizationDecision(
            auth.get() != null
                && auth.get().getPrincipal() instanceof Jwt jwt
                && rule.test(AdminRole.from(OperatorResolver.realmRoles(jwt))));
  }

  /** Signature from the realm's JWKS (fetched on first use), expiry, and the realm in {@code iss}. */
  @Bean
  JwtDecoder jwtDecoder(AdminApiProperties props) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(props.jwt().jwkSetUri()).build();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefault(), new RealmIssuerValidator(props.jwt().realmPath())));
    return decoder;
  }
}

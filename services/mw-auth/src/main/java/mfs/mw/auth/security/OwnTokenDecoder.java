package mfs.mw.auth.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.util.List;
import java.util.Set;
import mfs.mw.auth.config.TokenProperties;
import mfs.mw.auth.service.JwksService;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * The verifier of mw-auth's own access tokens (logout, first PIN; ticket 69): RS256 only, header
 * {@code typ at+jwt}, the key by {@code kid} from the JWT_SIGN set ({@link JwksService}, refetched
 * once on an unknown kid), expiry, the exact issuer and audience of {@link TokenProperties}, and a
 * {@code sid}.
 */
public final class OwnTokenDecoder {

  private OwnTokenDecoder() {}

  public static JwtDecoder create(JwksService jwks, TokenProperties props) {
    JWKSource<SecurityContext> keys = (JWKSelector selector, SecurityContext ctx) -> {
      Set<String> kids = selector.getMatcher().getKeyIDs();
      String kid = kids == null || kids.isEmpty() ? null : kids.iterator().next();
      List<JWK> found = selector.select(jwks.signingKeySet(kid));
      return found;
    };
    DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
    processor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(new JOSEObjectType("at+jwt")));
    processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
    // claims are checked by the Spring validators below
    processor.setJWTClaimsSetVerifier((claims, ctx) -> {});
    NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
        // not JwtValidators.createDefault*: since Spring Security 7 they accept typ JWT only, and the
        // header typ at+jwt is checked by the processor above
        new JwtTimestampValidator(),
        new JwtIssuerValidator(props.issuer()),
        new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(props.audience())),
        jwt -> jwt.getClaimAsString("sid") != null && jwt.getSubject() != null
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "no sid or sub", null))));
    return decoder;
  }
}

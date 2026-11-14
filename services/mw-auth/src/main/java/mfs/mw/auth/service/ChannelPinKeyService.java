package mfs.mw.auth.service;

import mfs.mw.auth.config.AssertionProperties;
import mfs.mw.auth.dto.InternalPinKey;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The PIN transport key for the channels (security review of ticket 72): the same newest {@code
 * PIN_TRANSPORT} version as {@code GET /auth/v1/pin-key} ({@link JwksService#pinKey}, from mw-crypto
 * over mTLS), served on the internal mTLS port to the callers of the assertion ({@code
 * mw.auth.assertion.callers}; anyone else 403 MW-AUTH-002) with its purpose named. The public
 * document stays for the apps only.
 */
@Service
public class ChannelPinKeyService {

  private final JwksService jwks;
  private final AssertionProperties props;

  public ChannelPinKeyService(JwksService jwks, AssertionProperties props) {
    this.jwks = jwks;
    this.props = props;
  }

  public InternalPinKey pinKey(String caller) {
    if (caller == null || !props.callers().contains(caller)) {
      throw new MwException(ErrorCodes.AUTH_FORBIDDEN, "Not allowed").withStatus(HttpStatus.FORBIDDEN);
    }
    return InternalPinKey.of(jwks.pinKey());
  }
}

package mfs.mw.crypto.dto;

import java.util.List;

/**
 * Answer of {@code POST /internal/v1/tokens}: {@code tokens.get(i)} is the token of {@code
 * values.get(i)}, {@code <DOMAIN>_<base32 of the HMAC>}. Tokens are search keys, not secrets.
 *
 * @param kid the {@code TOKENISE_<domain>} key version (a rotation gives new tokens)
 */
public record TokensView(String domain, String kid, List<String> tokens) {

  public TokensView {
    tokens = List.copyOf(tokens);
  }
}

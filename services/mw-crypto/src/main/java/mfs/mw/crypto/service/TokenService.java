package mfs.mw.crypto.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import mfs.mw.crypto.client.Capability;
import mfs.mw.crypto.dto.TokeniseRequest;
import mfs.mw.crypto.dto.TokensView;
import mfs.mw.crypto.service.HsmKeys.Resolved;
import mfs.mw.crypto.util.Bytes;
import org.springframework.stereotype.Service;

/**
 * Deterministic tokenisation (spec 18.8: data warehouse export, audit search keys): the token of a
 * value is {@code <DOMAIN>_} and the base32 (RFC 4648, no padding) of HMAC-SHA256 under the ACTIVE
 * {@code TOKENISE_<domain>} key over its UTF-8 bytes. The same value gives the same token until the
 * key is rotated. One HSM session per batch.
 */
@Service
public class TokenService {

  private final HsmKeys keys;

  public TokenService(HsmKeys keys) {
    this.keys = keys;
  }

  public TokensView tokenise(TokeniseRequest r) {
    String domain = r.domain().name();
    Resolved key = keys.activeByPurpose("TOKENISE_" + domain, p -> p.capability() == Capability.TOKENISE, "tokenise");
    List<byte[]> values = r.values().stream().map(v -> v.getBytes(StandardCharsets.UTF_8)).toList();
    List<byte[]> macs = keys.run("tokenise", key, p -> p.hmac(key.label(), values));
    values.forEach(Bytes::zero);
    return new TokensView(domain, key.kid(), macs.stream().map(m -> domain + "_" + Bytes.base32(m)).toList());
  }
}

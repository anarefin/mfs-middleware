package mfs.mw.compliance.service;

import java.nio.charset.StandardCharsets;
import mfs.mw.compliance.client.CryptoClient;
import mfs.mw.compliance.dto.CryptoEnvelope;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Personal data at rest (FRS FL-02, CMP-05): every value is sealed in an mw-crypto envelope under {@code
 * KEK_COLUMN}, bound (AAD) to its row and column ({@code <table>/<id>/<column>}) so an envelope moved to another row
 * does not open, and stored as the envelope's JSON (jsonb). The NID is searched by its {@code TOKENISE_NID} token.
 */
@Service
public class PiiCipher {

  private final CryptoClient crypto;
  private final JsonMapper json;

  public PiiCipher(CryptoClient crypto, JsonMapper json) {
    this.crypto = crypto;
    this.json = json;
  }

  public String seal(String plaintext, String aad) {
    return sealBytes(plaintext.getBytes(StandardCharsets.UTF_8), aad);
  }

  public String sealBytes(byte[] plaintext, String aad) {
    CryptoEnvelope e = crypto.encrypt(CryptoClient.KEK_COLUMN, plaintext, aad.getBytes(StandardCharsets.UTF_8));
    return json.writeValueAsString(e);
  }

  public String open(String envelopeJson) {
    return new String(crypto.decrypt(json.readValue(envelopeJson, CryptoEnvelope.class)), StandardCharsets.UTF_8);
  }

  public String tokeniseNid(String nid) {
    return crypto.tokeniseNid(nid);
  }
}

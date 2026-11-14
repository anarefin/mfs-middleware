package mfs.mw.crypto.service;

import java.io.ByteArrayOutputStream;
import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.SignatureException;
import java.security.SignatureSpi;
import java.util.Map;
import mfs.mw.crypto.client.SignatureAlgorithm;

/**
 * A JCA provider with one {@code SHA256withRSA} signature whose private key stays in the HSM, for the
 * JDK XML-DSig signer (ticket 65): {@code XmlDsigService} hands it to the {@code DOMSignContext}
 * (property {@code org.jcp.xml.dsig.internal.dom.SignatureProvider}) with a {@link HsmKey} that only
 * names the key version; the canonical {@code SignedInfo} goes to {@code CryptoProvider.sign}. It
 * signs only, and it is never registered with {@code Security}, so nothing else can pick it.
 */
final class HsmXmlSignatureProvider extends Provider {

  private static final long serialVersionUID = 1L;

  /** A reference to one key version in the HSM; it holds no key material. */
  record HsmKey(HsmKeys keys, HsmKeys.Resolved key) implements PrivateKey {
    @Override
    public String getAlgorithm() {
      return "RSA";
    }

    @Override
    public String getFormat() {
      return null;
    }

    @Override
    public byte[] getEncoded() {
      return null;
    }
  }

  HsmXmlSignatureProvider() {
    super("MwHsmXmlDsig", "1.0", "mw-crypto: SHA256withRSA in the HSM for XML-DSig");
    putService(new Service(this, "Signature", "SHA256withRSA", Spi.class.getName(), null, Map.of()) {
      @Override
      public Object newInstance(Object constructorParameter) {
        return new Spi();
      }
    });
  }

  /** Collects the canonical {@code SignedInfo} and signs it in the HSM. */
  @SuppressWarnings("deprecation")
  static final class Spi extends SignatureSpi {
    private final ByteArrayOutputStream data = new ByteArrayOutputStream();
    private HsmKey key;

    @Override
    protected void engineInitSign(PrivateKey privateKey) throws InvalidKeyException {
      if (!(privateKey instanceof HsmKey k)) {
        throw new InvalidKeyException("not an HSM key reference");
      }
      key = k;
      data.reset();
    }

    @Override
    protected void engineInitVerify(PublicKey publicKey) throws InvalidKeyException {
      throw new InvalidKeyException("sign only");
    }

    @Override
    protected void engineUpdate(byte b) {
      data.write(b);
    }

    @Override
    protected void engineUpdate(byte[] b, int off, int len) {
      data.write(b, off, len);
    }

    @Override
    protected byte[] engineSign() throws SignatureException {
      if (key == null) {
        throw new SignatureException("not initialised");
      }
      byte[] input = data.toByteArray();
      data.reset();
      HsmKeys.Resolved k = key.key();
      return key.keys().run("xmldsig-sign", k, p -> p.sign(k.label(), SignatureAlgorithm.RS256, input));
    }

    @Override
    protected boolean engineVerify(byte[] sigBytes) throws SignatureException {
      throw new SignatureException("sign only");
    }

    @Override
    protected void engineSetParameter(String param, Object value) {
      throw new UnsupportedOperationException();
    }

    @Override
    protected Object engineGetParameter(String param) {
      throw new UnsupportedOperationException();
    }
  }
}

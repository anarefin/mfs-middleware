package mfs.mw.audit.dw.client;

import java.util.List;

/**
 * Deterministic keyed tokens of MSISDNs (ticket 130): mw-crypto {@code POST /internal/v1/tokens}
 * domain {@code MSISDN} under {@code TOKENISE_MSISDN} ({@link CryptoClient}). The search projection
 * stores only these tokens, and an MSISDN search tokenises the operator's input the same way.
 */
public interface MsisdnTokeniser {

  /**
   * @param msisdns normalised MSISDNs ({@code 01XXXXXXXXX}), 1..500
   * @return one token per input, in order
   * @throws mfs.mw.audit.dw.exception.CryptoUnavailableException when mw-crypto cannot answer
   */
  List<String> tokenise(List<String> msisdns);
}

package mfs.mw.data.export.service;

/**
 * Turns one clear value into a token for {@code domain} ({@code MSISDN}, {@code NID} or {@code
 * ACCOUNT}). The production implementation calls mw-crypto; tests supply a fixed stand-in.
 */
@FunctionalInterface
public interface FieldTokeniser {

  String tokenise(String domain, String value);
}

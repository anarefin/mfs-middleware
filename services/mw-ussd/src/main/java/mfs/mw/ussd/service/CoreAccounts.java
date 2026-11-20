package mfs.mw.ussd.service;

import java.util.Optional;

/** The customer's account type from the core (WAL-01), used to admit a short code. */
public interface CoreAccounts {

  /** The account {@code type}, or empty when the core has no such account. */
  Optional<String> type(String msisdn);
}

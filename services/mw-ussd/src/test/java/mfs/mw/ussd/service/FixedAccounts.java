package mfs.mw.ussd.service;

import java.util.Optional;

/** One account type for every MSISDN. Empty {@code type} is an unknown account. */
final class FixedAccounts implements CoreAccounts {

  String type = "CUSTOMER";

  @Override
  public Optional<String> type(String msisdn) {
    return type == null || type.isBlank() ? Optional.empty() : Optional.of(type);
  }
}

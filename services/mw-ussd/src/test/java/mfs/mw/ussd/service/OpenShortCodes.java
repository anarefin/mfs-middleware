package mfs.mw.ussd.service;

import java.util.Optional;

/** No table rows: the engine keeps the yaml short-code check. */
final class OpenShortCodes implements ShortCodeBook {

  @Override
  public boolean configured(String mno) {
    return false;
  }

  @Override
  public Optional<ShortCode> find(String mno, String code) {
    return Optional.empty();
  }
}

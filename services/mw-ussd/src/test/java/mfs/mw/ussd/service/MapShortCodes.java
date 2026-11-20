package mfs.mw.ussd.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** In-memory short codes for one test. */
final class MapShortCodes implements ShortCodeBook {

  private final Map<String, ShortCode> rows = new HashMap<>();

  void add(ShortCode code) {
    rows.put(code.mno() + "|" + code.code(), code);
  }

  @Override
  public boolean configured(String mno) {
    return rows.keySet().stream().anyMatch(k -> k.startsWith(mno + "|"));
  }

  @Override
  public Optional<ShortCode> find(String mno, String code) {
    return Optional.ofNullable(rows.get(mno + "|" + code));
  }
}

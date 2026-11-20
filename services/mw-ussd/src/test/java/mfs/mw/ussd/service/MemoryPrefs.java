package mfs.mw.ussd.service;

import java.util.HashMap;
import java.util.Map;

/** Language by MSISDN, default Bangla, for engine tests that do not use PostgreSQL. */
final class MemoryPrefs implements CustomerPrefs {

  final Map<String, String> langs = new HashMap<>();

  @Override
  public String language(String msisdn) {
    return langs.getOrDefault(msisdn, "bn");
  }

  @Override
  public void remember(String msisdn, String language) {
    langs.put(msisdn, language);
  }
}

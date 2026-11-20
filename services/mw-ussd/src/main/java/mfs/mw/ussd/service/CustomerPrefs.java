package mfs.mw.ussd.service;

/** Language remembered for an MSISDN (CH-USSD-10). Absent means Bangla. The number itself is not stored. */
public interface CustomerPrefs {

  String language(String msisdn);

  void remember(String msisdn, String language);
}

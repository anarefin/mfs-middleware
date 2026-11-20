package mfs.mw.ussd.service;

import java.util.Optional;

/** The short-code table. An MNO with no rows keeps the yaml {@code short-codes} check. */
public interface ShortCodeBook {

  boolean configured(String mno);

  Optional<ShortCode> find(String mno, String code);
}

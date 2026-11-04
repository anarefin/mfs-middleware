package mfs.mw.simulators.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import mfs.mw.simulators.dto.SimMode;

/**
 * Amount magic values shared by every simulator that takes money: the fraction .13 is a timeout,
 * .77 a 5xx after send, .99 an unknown code, .55 a malformed reply; anything else succeeds.
 */
public final class MagicAmounts {

  private MagicAmounts() {}

  public static SimMode mode(String amount) {
    int cents =
        new BigDecimal(amount)
            .movePointRight(2)
            .toBigInteger()
            .mod(BigInteger.valueOf(100))
            .intValue();
    return switch (cents) {
      case 13 -> SimMode.TIMEOUT;
      case 77 -> SimMode.SERVER_ERROR_AFTER_SEND;
      case 99 -> SimMode.UNKNOWN_CODE;
      case 55 -> SimMode.MALFORMED;
      default -> SimMode.SUCCESS;
    };
  }
}

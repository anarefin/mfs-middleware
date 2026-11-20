package mfs.mw.notify.service;

import mfs.mw.notify.dto.Priority;

/**
 * Promotional opt-out (FRS NOT-09, ticket 111). An opted-out MSISDN is skipped for {@link
 * Priority#PROMO} only. OTP and transactional messages are never opted out of.
 */
public final class PromoOptOut {

  private PromoOptOut() {}

  public static boolean blocks(Priority priority, boolean optedOut) {
    return optedOut && priority == Priority.PROMO;
  }
}

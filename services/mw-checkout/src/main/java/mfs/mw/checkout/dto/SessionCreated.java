package mfs.mw.checkout.dto;

import java.time.Instant;

/** Answer to {@code POST /checkout/v1/sessions}: where to send the customer, and until when. */
public record SessionCreated(String sessionId, String checkoutUrl, SessionStatus status, Instant expiresAt) {}

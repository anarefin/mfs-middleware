package mfs.mw.notify.dto;

import mfs.mw.notify.util.Sensitive;
import mfs.mw.notify.util.SensitiveType;

/**
 * One push to the app-server push API (ticket 110): a rendered PUSH template (title + body) for one
 * device token.
 *
 * @param messageId the notification's messageId (sent along, so the push server can tell repeats)
 * @param platform ANDROID or IOS, as the app registered it
 * @param txnId the transaction, sent as {@code data.txnId} for the app's deep link (may be null)
 */
public record PushMessage(
    String messageId,
    @Sensitive(SensitiveType.PUSH_TOKEN) String token,
    String platform,
    String title,
    @Sensitive(SensitiveType.SECRET) String body,
    String txnId) {}

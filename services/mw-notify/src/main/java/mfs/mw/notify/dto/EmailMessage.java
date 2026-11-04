package mfs.mw.notify.dto;

import mfs.mw.notify.util.Sensitive;
import mfs.mw.notify.util.SensitiveType;

/** One rendered email (spec 10.2): text part always, HTML part when the template has one. */
public record EmailMessage(
    @Sensitive(SensitiveType.SECRET) String to,
    String subject,
    @Sensitive(SensitiveType.SECRET) String text,
    @Sensitive(SensitiveType.SECRET) String html) {}

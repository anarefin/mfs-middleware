package mfs.mw.notify.entity;

/** The active version of a row of {@code notify.template} (spec 10.3). */
public record TemplateRecord(
    String code,
    String channel,
    String locale,
    int version,
    String subject,
    String body,
    String senderId,
    // EMAIL: the HTML part (params HTML-escaped); null = text only.
    String htmlBody) {}

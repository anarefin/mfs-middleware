package mfs.mw.admin.api.dto;

import java.util.List;

/**
 * {@code POST /admin/v1/notify-campaigns} (ticket 111, NOT-07): a promotional campaign. The
 * audience is an MSISDN list, a CSV of the same, a customer type, or a combination. At least one
 * is required; the service checks that (the derived schema has no pattern or format).
 */
public record NotifyCampaignBody(
    String templateCode,
    String locale,
    String scheduleAt,
    Integer throttlePerSecond,
    String customerType,
    List<String> msisdns,
    String csv,
    String text,
    String reason) {}

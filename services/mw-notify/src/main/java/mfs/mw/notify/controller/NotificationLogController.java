package mfs.mw.notify.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import mfs.mw.notify.dto.NotificationLogItem;
import mfs.mw.notify.dto.NotificationLogQuery;
import mfs.mw.notify.service.NotificationLogService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /internal/v1/notifications} (ticket 111, NOT-08): the notification log, paged and
 * masked, for the admin view (ticket 134). Internal JWT ({@code config/SecurityConfig}). An OTP
 * body's text is never returned.
 */
@RestController
@Validated
@RequestMapping("/internal/v1/notifications")
public class NotificationLogController {

  private final NotificationLogService logs;

  public NotificationLogController(NotificationLogService logs) {
    this.logs = logs;
  }

  @GetMapping
  public NotificationLogItem.Page search(
      @RequestParam(required = false) @Size(max = 20) String msisdn,
      @RequestParam(required = false) @Size(max = 128) String txnId,
      @RequestParam(required = false) @Size(max = 16) String channel,
      @RequestParam(required = false) @Size(max = 32) String status,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(100_000) int offset) {
    return logs.search(new NotificationLogQuery(msisdn, txnId, channel, status, from, to, limit, offset));
  }
}

package mfs.mw.notify.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import mfs.mw.notify.dto.InboxMessage;
import mfs.mw.notify.service.InboxService;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer inbox (ticket 107a). Caller {@code mw-app-gateway} only ({@code
 * security/InternalCallerPolicy}). OTP messages are omitted. Rows are limited to the requested
 * MSISDN. This is not {@code GET /internal/v1/notifications} (ticket 111, the admin log).
 */
@RestController
@Validated
@RequestMapping("/internal/v1/inbox")
public class InboxController {

  private final InboxService inbox;

  public InboxController(InboxService inbox) {
    this.inbox = inbox;
  }

  @GetMapping
  public InboxMessage.Page list(
      @RequestParam @Size(max = 20) String msisdn,
      @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int offset) {
    return inbox.page(msisdn, limit, offset);
  }

  @PostMapping("/{id}/read")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void read(@PathVariable @Size(max = 200) String id, @RequestParam @Size(max = 20) String msisdn) {
    inbox.markRead(msisdn, id);
  }
}

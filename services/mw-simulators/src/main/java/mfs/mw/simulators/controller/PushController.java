package mfs.mw.simulators.controller;

import java.util.List;
import mfs.mw.simulators.dto.PushMessage;
import mfs.mw.simulators.dto.PushMessageRequest;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.service.PushService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The push server mock (ticket 110, FRS EXT-04): {@code POST /push/v1/messages} is the app-server push API
 * mw-notify calls; {@code GET /push/v1/messages[?token=&topic=]} lists what it accepted (the test inbox).
 */
@RestController
public class PushController {

  private final PushService push;

  public PushController(PushService push) {
    this.push = push;
  }

  @PostMapping("/push/v1/messages")
  ResponseEntity<Object> send(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestBody(required = false) PushMessageRequest request) {
    SimReply reply = push.send(authorization, request);
    return ResponseEntity.status(reply.httpStatus()).contentType(MediaType.APPLICATION_JSON).body(reply.body());
  }

  /** The inbox; {@code /sim/push/messages} is the same list on the control path the test-only route reaches. */
  @GetMapping({"/push/v1/messages", "/sim/push/messages"})
  List<PushMessage> messages(@RequestParam(required = false) String token, @RequestParam(required = false) String topic) {
    return push.messages(token, topic);
  }
}

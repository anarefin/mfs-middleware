package mfs.mw.simulators.controller;

import java.util.List;
import mfs.mw.simulators.dto.SmscMessage;
import mfs.mw.simulators.service.SmscService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Control API of the SMSC simulator (ticket 24): what it received, for test assertions. */
@RestController
@RequestMapping("/sim/smsc/messages")
public class SmscController {

  private final SmscService smsc;

  public SmscController(SmscService smsc) {
    this.smsc = smsc;
  }

  @GetMapping
  public List<SmscMessage> messages(@RequestParam(required = false) String msisdn) {
    return smsc.messages(msisdn);
  }

  @DeleteMapping
  public ResponseEntity<Void> reset() {
    smsc.reset();
    return ResponseEntity.noContent().build();
  }
}

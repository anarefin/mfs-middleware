package mfs.mw.ussd.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import mfs.mw.ussd.dto.HttpUssdRequest;
import mfs.mw.ussd.dto.HttpUssdResponse;
import mfs.mw.ussd.service.HttpUssdTransport;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** MNO-facing HTTP USSD endpoint (spec 9.2), behind the APISIX route {@code /ussd/*}. */
@RestController
@Validated
public class UssdHttpController {

  private final HttpUssdTransport transport;

  public UssdHttpController(HttpUssdTransport transport) {
    this.transport = transport;
  }

  @PostMapping(path = "/ussd/{mno}", consumes = "application/json", produces = "application/json")
  public HttpUssdResponse step(
      @PathVariable @Pattern(regexp = "[a-z]{1,16}") String mno, @Valid @RequestBody HttpUssdRequest body) {
    return transport.handle(mno, body);
  }
}

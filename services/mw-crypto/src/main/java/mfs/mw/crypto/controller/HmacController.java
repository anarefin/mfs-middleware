package mfs.mw.crypto.controller;

import jakarta.validation.Valid;
import mfs.mw.crypto.dto.HmacSignRequest;
import mfs.mw.crypto.dto.HmacVerifyRequest;
import mfs.mw.crypto.dto.HmacView;
import mfs.mw.crypto.service.HmacService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HMAC sign and verify with {@code HMAC_<use>} keys (crypto-internal.v1, spec 18.8). */
@RestController
@RequestMapping("/internal/v1/hmac")
public class HmacController {

  private final HmacService hmac;

  public HmacController(HmacService hmac) {
    this.hmac = hmac;
  }

  @PostMapping("/sign")
  public HmacView sign(@Valid @RequestBody HmacSignRequest request) {
    return hmac.sign(request);
  }

  @PostMapping("/verify")
  public HmacView verify(@Valid @RequestBody HmacVerifyRequest request) {
    return hmac.verify(request);
  }
}

package mfs.mw.crypto.controller;

import jakarta.validation.Valid;
import mfs.mw.crypto.dto.TranslatePinBlockRequest;
import mfs.mw.crypto.dto.TranslatedPinBlock;
import mfs.mw.crypto.service.PinBlockService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** ISO 9564 PIN block translation on the payShield provider (crypto-internal.v1, ticket 64). */
@RestController
public class PinBlockController {

  private final PinBlockService service;

  public PinBlockController(PinBlockService service) {
    this.service = service;
  }

  @PostMapping("/internal/v1/pinblock/translate")
  public TranslatedPinBlock translate(@Valid @RequestBody TranslatePinBlockRequest request) {
    return service.translate(request);
  }
}

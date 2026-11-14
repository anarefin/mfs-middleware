package mfs.mw.crypto.controller;

import jakarta.validation.Valid;
import mfs.mw.crypto.dto.PvvRequest;
import mfs.mw.crypto.dto.PvvView;
import mfs.mw.crypto.service.PinBlockService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Visa PVV generate and verify on the payShield provider (crypto-internal.v1, ticket 64). */
@RestController
@RequestMapping("/internal/v1/pvv")
public class PvvController {

  private final PinBlockService service;

  public PvvController(PinBlockService service) {
    this.service = service;
  }

  @PostMapping("/generate")
  public PvvView generate(@Valid @RequestBody PvvRequest request) {
    return service.generatePvv(request);
  }

  @PostMapping("/verify")
  public PvvView verify(@Valid @RequestBody PvvRequest request) {
    return service.verifyPvv(request);
  }
}

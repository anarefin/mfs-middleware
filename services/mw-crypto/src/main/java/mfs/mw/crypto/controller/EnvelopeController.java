package mfs.mw.crypto.controller;

import jakarta.validation.Valid;
import mfs.mw.crypto.dto.Envelope;
import mfs.mw.crypto.dto.EnvelopeEncryptRequest;
import mfs.mw.crypto.dto.PlaintextView;
import mfs.mw.crypto.service.EnvelopeService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Envelope encryption under a KEK in the HSM (crypto-internal.v1, spec 18.8). */
@RestController
@RequestMapping("/internal/v1/envelope")
public class EnvelopeController {

  private final EnvelopeService envelopes;

  public EnvelopeController(EnvelopeService envelopes) {
    this.envelopes = envelopes;
  }

  @PostMapping("/encrypt")
  public Envelope encrypt(@Valid @RequestBody EnvelopeEncryptRequest request) {
    return envelopes.encrypt(request);
  }

  @PostMapping("/decrypt")
  public PlaintextView decrypt(@Valid @RequestBody Envelope envelope) {
    return envelopes.decrypt(envelope);
  }
}

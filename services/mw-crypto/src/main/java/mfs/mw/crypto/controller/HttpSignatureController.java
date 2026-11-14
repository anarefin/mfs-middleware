package mfs.mw.crypto.controller;

import jakarta.validation.Valid;
import mfs.mw.crypto.dto.HttpSignatureSignRequest;
import mfs.mw.crypto.dto.HttpSignatureSignResponse;
import mfs.mw.crypto.dto.HttpSignatureVerifyRequest;
import mfs.mw.crypto.dto.SignatureCheck;
import mfs.mw.crypto.service.HttpSignatureService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** RFC 9421 HTTP message signatures, sign and verify (crypto-internal.v1, spec 18.8, ticket 65). */
@RestController
@RequestMapping("/internal/v1/http-signature")
public class HttpSignatureController {

  private final HttpSignatureService signatures;

  public HttpSignatureController(HttpSignatureService signatures) {
    this.signatures = signatures;
  }

  @PostMapping("/sign")
  public HttpSignatureSignResponse sign(@Valid @RequestBody HttpSignatureSignRequest request) {
    return signatures.sign(request);
  }

  @PostMapping("/verify")
  public SignatureCheck verify(@Valid @RequestBody HttpSignatureVerifyRequest request) {
    return signatures.verify(request);
  }
}

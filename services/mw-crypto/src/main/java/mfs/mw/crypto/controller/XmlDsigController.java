package mfs.mw.crypto.controller;

import jakarta.validation.Valid;
import mfs.mw.crypto.dto.SignatureCheck;
import mfs.mw.crypto.dto.XmlDsigSignRequest;
import mfs.mw.crypto.dto.XmlDsigSignResponse;
import mfs.mw.crypto.dto.XmlDsigVerifyRequest;
import mfs.mw.crypto.service.XmlDsigService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** XML-DSig sign and verify (crypto-internal.v1, spec 18.8, ticket 65). */
@RestController
@RequestMapping("/internal/v1/xmldsig")
public class XmlDsigController {

  private final XmlDsigService xmldsig;

  public XmlDsigController(XmlDsigService xmldsig) {
    this.xmldsig = xmldsig;
  }

  @PostMapping("/sign")
  public XmlDsigSignResponse sign(@Valid @RequestBody XmlDsigSignRequest request) {
    return xmldsig.sign(request);
  }

  @PostMapping("/verify")
  public SignatureCheck verify(@Valid @RequestBody XmlDsigVerifyRequest request) {
    return xmldsig.verify(request);
  }
}

package mfs.mw.core.mock.controller;

import mfs.mw.core.mock.dto.PinPublicKey;
import mfs.mw.core.mock.security.PinKeyProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test/admin API of the mock (not a product contract): the key channels encrypt PINs with. */
@RestController
@RequestMapping("/core-mock/v1")
public class PinKeyController {

  private final PinKeyProvider keys;

  public PinKeyController(PinKeyProvider keys) {
    this.keys = keys;
  }

  @GetMapping("/pin-public-key")
  PinPublicKey publicKey() {
    return new PinPublicKey(keys.keyId(), "RSA-OAEP-SHA-256", keys.publicKeyPem());
  }
}

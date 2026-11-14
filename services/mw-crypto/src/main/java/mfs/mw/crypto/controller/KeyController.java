package mfs.mw.crypto.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import mfs.mw.crypto.dto.GenerateKeyRequest;
import mfs.mw.crypto.dto.KeyView;
import mfs.mw.crypto.dto.PublicKeyView;
import mfs.mw.crypto.service.KeyService;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Key management API (contracts/api/mw-crypto/crypto-internal.v1.openapi.yaml, spec 18.8). Behind
 * the internal service JWT ({@code config/SecurityConfig}). {@code POST /keys} is for dev and the
 * bootstrap until the key ceremony (ticket 75) takes over generation.
 */
@RestController
@Validated
@RequestMapping("/internal/v1/keys")
public class KeyController {

  static final String KEY_ID = "[a-z0-9][a-z0-9-]{0,62}";

  private final KeyService keys;

  public KeyController(KeyService keys) {
    this.keys = keys;
  }

  @GetMapping
  public KeyView.Page list() {
    return keys.list();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public KeyView generate(@Valid @RequestBody GenerateKeyRequest request) {
    return keys.generate(request.purpose(), request.keyId());
  }

  @PostMapping("/{keyId}/rotate")
  public KeyView rotate(@PathVariable @Pattern(regexp = KEY_ID) String keyId) {
    return keys.rotate(keyId);
  }

  @GetMapping("/{keyId}/public")
  public PublicKeyView publicKey(@PathVariable @Pattern(regexp = KEY_ID) String keyId) {
    return keys.publicKeys(keyId);
  }
}

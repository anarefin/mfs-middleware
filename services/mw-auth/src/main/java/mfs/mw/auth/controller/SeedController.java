package mfs.mw.auth.controller;

import jakarta.validation.Valid;
import mfs.mw.auth.dto.SeedPinsRequest;
import mfs.mw.auth.dto.SeedPinsResult;
import mfs.mw.auth.service.SeedPinService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev only (ticket 69): the seeder's first PINs ({@code auth-internal.v1} {@code seedPins}). Exists only
 * with {@code mw.auth.seed-api.enabled=true}; then {@code config/SecurityConfig} lets it through
 * without a token on either port (the seeder has no client certificate; APISIX never routes
 * {@code /internal/*}).
 */
@RestController
@ConditionalOnProperty(name = "mw.auth.seed-api.enabled", havingValue = "true")
public class SeedController {

  private final SeedPinService seeds;

  public SeedController(SeedPinService seeds) {
    this.seeds = seeds;
  }

  @PostMapping("/internal/v1/seed/pins")
  public SeedPinsResult pins(@Valid @RequestBody SeedPinsRequest body) {
    return seeds.seed(body);
  }
}

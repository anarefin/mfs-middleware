package mfs.mw.orchestrator.controller;

import jakarta.validation.Valid;
import mfs.mw.orchestrator.dto.Quote;
import mfs.mw.orchestrator.dto.QuoteRequest;
import mfs.mw.orchestrator.service.QuoteService;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The quote before the PIN (ticket 105, contract {@code api/mw-orchestrator/txn-query.v1.openapi.yaml}
 * 1.4.0): mw-app-gateway's preview asks what a transaction of the signed-in wallet would cost. A read;
 * only a wallet-scoped caller may ask ({@code security/InternalCallerPolicy}).
 */
@RestController
@RequestMapping("/internal/v1/quotes")
public class QuoteController {

  private final QuoteService quotes;

  public QuoteController(QuoteService quotes) {
    this.quotes = quotes;
  }

  @PostMapping
  public Quote quote(@Valid @RequestBody QuoteRequest body) {
    return quotes.quote(body, MDC.get("correlationId"));
  }
}

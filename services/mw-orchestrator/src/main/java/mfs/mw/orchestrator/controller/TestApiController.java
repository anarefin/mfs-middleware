package mfs.mw.orchestrator.controller;

import jakarta.validation.Valid;
import java.util.UUID;
import mfs.mw.orchestrator.dto.DlqRecord;
import mfs.mw.orchestrator.dto.PoisonAccepted;
import mfs.mw.orchestrator.dto.TestReversalAccepted;
import mfs.mw.orchestrator.dto.TestReversalRequest;
import mfs.mw.orchestrator.dto.TxnRequestHandled;
import mfs.mw.orchestrator.service.TestApiService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only API (ticket 58, {@code contracts/api/mw-orchestrator/orchestrator-test.v1.openapi.yaml}):
 * the host API suite plays operations' REVERSAL producer and checks that a request was taken in.
 * Only with {@code mw.orchestrator.test-api.enabled=true} ({@code MW_ORCHESTRATOR_TEST_API_ENABLED},
 * set by the dev stack); APISIX routes it only with {@code MW_E2E_ROUTES=1}.
 */
@RestController
@RequestMapping("/test/v1")
@ConditionalOnProperty(name = "mw.orchestrator.test-api.enabled", havingValue = "true")
public class TestApiController {

  private final TestApiService service;

  public TestApiController(TestApiService service) {
    this.service = service;
  }

  @PostMapping("/reversals")
  ResponseEntity<TestReversalAccepted> reverse(@Valid @RequestBody TestReversalRequest body) {
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.reverse(body.originalTxnId(), body.reason()));
  }

  @PostMapping("/poison-requests")
  ResponseEntity<PoisonAccepted> poison() {
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.poison());
  }

  @GetMapping("/dlq-records/{txnId}")
  DlqRecord dlqRecord(@PathVariable UUID txnId) {
    return service.dlqRecord(txnId.toString());
  }

  @GetMapping("/txn-requests/{idempotencyKey}")
  TxnRequestHandled handled(@PathVariable String idempotencyKey) {
    return service.handled(idempotencyKey);
  }
}

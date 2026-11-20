package mfs.mw.recon.controller;

import java.util.UUID;
import mfs.mw.recon.dto.RunSummary;
import mfs.mw.recon.service.ReconService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Operator reprocess (ticket 126). Not routed at the edge; mw-admin-api calls it and it is audited. */
@RestController
public class ReconController {

  private final ReconService recon;

  public ReconController(ReconService recon) {
    this.recon = recon;
  }

  @PostMapping("/internal/v1/recon/files/{id}/reprocess")
  public RunSummary reprocess(@PathVariable UUID id, @RequestHeader("X-Mw-Operator-Id") String operatorId) {
    return recon.reprocess(id, operatorId);
  }
}

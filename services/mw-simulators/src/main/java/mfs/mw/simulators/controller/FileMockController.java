package mfs.mw.simulators.controller;

import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import mfs.mw.simulators.dto.FileDropRequest;
import mfs.mw.simulators.dto.FileDropResult;
import mfs.mw.simulators.dto.FileRunRequest;
import mfs.mw.simulators.dto.FileRunResult;
import mfs.mw.simulators.dto.MockParty;
import mfs.mw.simulators.exception.ErrorCodes;
import mfs.mw.simulators.exception.MwException;
import mfs.mw.simulators.service.files.AmlMockService;
import mfs.mw.simulators.service.files.DwDropService;
import mfs.mw.simulators.service.files.FileInbox;
import mfs.mw.simulators.service.files.TrustBankService;
import mfs.mw.simulators.service.files.TrustStatementGenerator;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import mfs.mw.simulators.service.mockcontrol.MockParties;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /mock-control/{party}/run} (ticket 123; contract {@code mock-control.v1}): runs a file mock now, as
 * its scheduler job would. Only in the {@value MockControl#PROFILE} profile.
 */
@RestController
@Profile(MockControl.PROFILE)
public class FileMockController {

  private final AmlMockService aml;
  private final TrustBankService trustbank;
  private final DwDropService dw;
  private final FileInbox inbox;
  private final Clock clock;

  public FileMockController(
      AmlMockService aml, TrustBankService trustbank, DwDropService dw, FileInbox inbox, Clock clock) {
    this.aml = aml;
    this.trustbank = trustbank;
    this.dw = dw;
    this.inbox = inbox;
    this.clock = clock;
  }

  /** Test helper: a file into the aml or dw inbox, as the middleware would upload it. */
  @PostMapping("/mock-control/{party}/files")
  @ResponseStatus(HttpStatus.CREATED)
  FileDropResult drop(@PathVariable String party, @Valid @RequestBody FileDropRequest body) {
    return inbox.drop(party, body.name(), body.content());
  }

  @PostMapping("/mock-control/{party}/run")
  FileRunResult run(@PathVariable String party, @Valid @RequestBody(required = false) FileRunRequest body) {
    MockParty p = MockParties.require(party);
    if (!MockParties.isFileParty(party)) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Party " + party + " is not a file mock");
    }
    FileRunRequest req = body == null ? new FileRunRequest(null, null, null) : body;
    String op = req.operation() != null ? req.operation() : p.operations().get(0);
    if (!p.operations().contains(op)) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Party " + party + " has no operation " + op + "; it has " + p.operations());
    }
    LocalDateTime now = LocalDateTime.now(clock.withZone(TrustStatementGenerator.DHAKA)).truncatedTo(ChronoUnit.MINUTES);
    return switch (party) {
      case "aml" -> new FileRunResult(party, op, aml.run());
      case "dw" -> new FileRunResult(party, op, dw.run());
      default -> {
        if ("mt942".equals(op)) {
          LocalDate date = req.date() != null ? req.date() : now.toLocalDate();
          LocalTime at = req.at() != null ? LocalTime.parse(req.at()) : now.toLocalTime();
          yield new FileRunResult(party, op, trustbank.mt942(date, at));
        }
        yield new FileRunResult(party, op, trustbank.mt940(req.date() != null ? req.date() : now.toLocalDate().minusDays(1)));
      }
    };
  }
}

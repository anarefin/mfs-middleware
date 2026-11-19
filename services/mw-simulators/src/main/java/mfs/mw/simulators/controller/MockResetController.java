package mfs.mw.simulators.controller;

import jakarta.validation.Valid;
import mfs.mw.simulators.dto.ResetRequest;
import mfs.mw.simulators.dto.ResetResult;
import mfs.mw.simulators.service.mockcontrol.GlobalReset;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The global reset of every mock (FRS MCT-07, ticket 103; contract {@code mock-control.v1}). Only in the
 * {@value MockControl#PROFILE} profile.
 */
@RestController
@Profile(MockControl.PROFILE)
public class MockResetController {

  private final GlobalReset reset;

  public MockResetController(GlobalReset reset) {
    this.reset = reset;
  }

  @PostMapping("/mock-control/reset")
  ResetResult resetAll(@Valid @RequestBody(required = false) ResetRequest body) {
    return reset.all(body == null ? null : body.seedVersion());
  }
}

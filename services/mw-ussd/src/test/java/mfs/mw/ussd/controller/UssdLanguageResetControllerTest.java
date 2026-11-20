package mfs.mw.ussd.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import mfs.mw.ussd.repository.CustomerPrefRepository;
import org.junit.jupiter.api.Test;

class UssdLanguageResetControllerTest {

  @Test
  void resetForgetsTheRememberedLanguage() {
    CustomerPrefRepository prefs = mock(CustomerPrefRepository.class);
    new UssdLanguageResetController(prefs).reset(new UssdLanguageResetController.Reset("01711000001"));
    verify(prefs).forget("01711000001");
  }
}

package mfs.mw.e2e.steps;

import mfs.mw.e2e.support.UssdGateway;

/** The USSD session a scenario played (one instance per scenario, PicoContainer). */
public class UssdState {

  UssdGateway.Session session;

  UssdGateway.Session session() {
    if (session == null) {
      throw new IllegalStateException("no USSD session was played in this scenario");
    }
    return session;
  }
}

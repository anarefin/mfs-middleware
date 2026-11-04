package mfs.mw.ussd.repository;

import java.time.Duration;
import java.util.Optional;
import mfs.mw.ussd.dto.UssdSession;

/** USSD session state by {@code ussd:{mno}:{sessionId}} (spec 9.2). */
public interface UssdSessionStore {

  static String key(String mno, String sessionId) {
    return "ussd:" + mno + ":" + sessionId;
  }

  Optional<UssdSession> find(String mno, String sessionId);

  void save(UssdSession session, Duration ttl);

  void delete(String mno, String sessionId);
}

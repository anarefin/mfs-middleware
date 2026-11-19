package mfs.mw.ussd.repository;

import java.time.Duration;
import java.util.Optional;
import mfs.mw.ussd.dto.UssdSession;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** Sessions as JSON strings in Redis with the session TTL (spec 9.2). */
@Repository
public class RedisSessionStore implements UssdSessionStore {

  private final StringRedisTemplate redis;
  private final JsonMapper json;

  public RedisSessionStore(StringRedisTemplate redis, JsonMapper json) {
    this.redis = redis;
    this.json = json;
  }

  @Override
  public Optional<UssdSession> find(String mno, String sessionId) {
    String v = redis.opsForValue().get(UssdSessionStore.key(mno, sessionId));
    return v == null ? Optional.empty() : Optional.of(json.readValue(v, UssdSession.class));
  }

  @Override
  public void save(UssdSession session, Duration ttl) {
    redis.opsForValue()
        .set(UssdSessionStore.key(session.mno(), session.sessionId()), json.writeValueAsString(session), ttl);
  }

  @Override
  public void delete(String mno, String sessionId) {
    redis.delete(UssdSessionStore.key(mno, sessionId));
  }
}

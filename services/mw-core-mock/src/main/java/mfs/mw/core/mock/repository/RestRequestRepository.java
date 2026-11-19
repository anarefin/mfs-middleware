package mfs.mw.core.mock.repository;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The request hash of each core wallet API operation (ticket 93, {@code rest_request}): what makes a
 * repeat with another body a conflict. Callers hold the operation's advisory lock
 * ({@link CommandLogRepository#lock}).
 */
@Repository
public class RestRequestRepository {

  private final JdbcTemplate jdbc;

  public RestRequestRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<String> hashOf(String operationKey) {
    return jdbc.query("select request_hash from rest_request where operation_key = ?", (rs, i) -> rs.getString(1), operationKey)
        .stream()
        .findFirst();
  }

  public void save(String operationKey, String requestHash) {
    jdbc.update("insert into rest_request (operation_key, request_hash) values (?, ?)", operationKey, requestHash);
  }
}

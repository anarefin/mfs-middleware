package mfs.mw.notify.repository;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code notify.contact}: a party's registered email address (spec 10.2). */
@Repository
public class ContactRepository {

  private final JdbcTemplate jdbc;

  public ContactRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<String> emailOf(String partyId) {
    return jdbc.queryForList("select email from contact where party_id = ?", String.class, partyId)
        .stream()
        .findFirst();
  }
}

package mfs.mw.ussd.repository;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import mfs.mw.ussd.service.ShortCode;
import mfs.mw.ussd.service.ShortCodeBook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code ussd.short_code} (CH-USSD-08). */
@Repository
public class ShortCodeRepository implements ShortCodeBook {

  private final JdbcTemplate jdbc;

  public ShortCodeRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean configured(String mno) {
    Boolean any =
        jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM short_code WHERE mno = ?)", Boolean.class, mno);
    return Boolean.TRUE.equals(any);
  }

  @Override
  public Optional<ShortCode> find(String mno, String code) {
    List<ShortCode> rows =
        jdbc.query(
            """
            SELECT code, mno, root_menu_id, allowed_types
              FROM short_code
             WHERE mno = ? AND code = ?
            """,
            (rs, i) ->
                new ShortCode(
                    rs.getString("code"),
                    rs.getString("mno"),
                    rs.getString("root_menu_id"),
                    types(rs.getArray("allowed_types"))),
            mno,
            code);
    return rows.stream().findFirst();
  }

  private static List<String> types(Array array) {
    if (array == null) {
      return List.of();
    }
    try {
      Object raw = array.getArray();
      if (raw instanceof String[] strings) {
        return List.copyOf(Arrays.asList(strings));
      }
      if (raw instanceof Object[] objects) {
        return Arrays.stream(objects).map(String::valueOf).toList();
      }
      return List.of();
    } catch (SQLException e) {
      throw new IllegalStateException("short_code.allowed_types", e);
    }
  }
}

package mfs.mw.admin.api.config;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.content.*} (USSD menus and notify templates, ticket 39).
 *
 * @param mnos the MNO keys a USSD menu may be activated for (mw-ussd's {@code mw.ussd.mno.*})
 */
@Validated
@ConfigurationProperties("mw.content")
public record ContentProperties(@NotEmpty @DefaultValue({"gp", "robi", "bl", "teletalk"}) List<String> mnos) {

  public ContentProperties {
    mnos = List.copyOf(mnos);
  }
}

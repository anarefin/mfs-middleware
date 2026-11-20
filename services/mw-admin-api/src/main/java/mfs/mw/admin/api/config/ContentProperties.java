package mfs.mw.admin.api.config;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.content.*} (USSD menus and notify templates, ticket 39).
 *
 * @param mnos the MNO keys a USSD menu may be activated for (mw-ussd's {@code mw.ussd.mno.*})
 * @param ussd per MNO the short code, its root menu and the screen limits the preview uses (ticket
 *     133b); a copy of mw-ussd's {@code ussd.short_code} rows and {@code mw.ussd.mno.*} limits,
 *     kept aligned by hand (this service never reads mw-ussd's schema)
 */
@Validated
@ConfigurationProperties("mw.content")
public record ContentProperties(
    @NotEmpty @DefaultValue({"gp", "robi", "bl", "teletalk"}) List<String> mnos, Map<String, UssdMno> ussd) {

  public ContentProperties {
    mnos = List.copyOf(mnos);
    ussd = ussd == null ? Map.of() : Map.copyOf(ussd);
  }

  /**
   * One MNO's USSD entry point and screen.
   *
   * @param overflow {@code PAGE} or {@code TRUNCATE} (mw-ussd's overflow)
   */
  public record UssdMno(
      String shortCode,
      @DefaultValue("main") String rootMenuId,
      @DefaultValue("182") int maxLengthGsm,
      @DefaultValue("70") int maxLengthUcs2,
      @DefaultValue("PAGE") String overflow) {}
}

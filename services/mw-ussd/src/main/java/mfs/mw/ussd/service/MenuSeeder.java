package mfs.mw.ussd.service;

import java.time.Clock;
import mfs.mw.ussd.config.UssdProperties;
import mfs.mw.ussd.dto.Menu;
import mfs.mw.ussd.mapper.MenuParser;
import mfs.mw.ussd.repository.MenuRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Seeds the default menu ({@code mw.ussd.seed-menu}, classpath JSON) into {@code ussd.menu} on
 * start-up and activates it (go-live now) for every configured MNO that has no menu yet or whose
 * latest activation is an older version of the same seed menu (a dev stack moves from main v1 to
 * v2). Until the admin console edits menus (WP23, maker-checker), this is how a menu gets live; it
 * never changes an existing version or activation, nor replaces another menu. Blank switches it
 * off.
 */
@Component
public class MenuSeeder implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(MenuSeeder.class);

  private final MenuRepository menus;
  private final UssdProperties props;
  private final JsonMapper json;
  private final Clock clock;
  private final String resource;

  public MenuSeeder(
      MenuRepository menus,
      UssdProperties props,
      JsonMapper json,
      Clock clock,
      @Value("${mw.ussd.seed-menu:}") String resource) {
    this.menus = menus;
    this.props = props;
    this.json = json;
    this.clock = clock;
    this.resource = resource;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (resource == null || resource.isBlank()) {
      return;
    }
    String tree = MenuParser.resource(resource);
    Menu menu = MenuValidator.requireValid(MenuParser.parse(json, tree));
    if (menus.insertIfAbsent(menu.menuId(), menu.version(), tree)) {
      log.atInfo()
          .addKeyValue("event", "ussd.menu.seeded")
          .addKeyValue("menuId", menu.menuId())
          .addKeyValue("menuVersion", menu.version())
          .log("default menu stored");
    }
    for (String mno : props.mno().keySet()) {
      boolean supersede =
          menus.latestActivation(mno)
              .map(v -> v.menuId().equals(menu.menuId()) && v.version() < menu.version())
              .orElse(true);
      if (supersede) {
        menus.activate(mno, menu.menuId(), menu.version(), clock.instant());
        log.atInfo()
            .addKeyValue("event", "ussd.menu.activated")
            .addKeyValue("mno", mno)
            .addKeyValue("menuId", menu.menuId())
            .addKeyValue("menuVersion", menu.version())
            .log("default menu live for MNO");
      }
    }
  }
}

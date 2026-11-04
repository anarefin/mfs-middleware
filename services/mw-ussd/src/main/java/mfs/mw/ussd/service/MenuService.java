package mfs.mw.ussd.service;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import mfs.mw.ussd.dto.Menu;
import mfs.mw.ussd.entity.MenuVersion;
import mfs.mw.ussd.exception.ErrorCodes;
import mfs.mw.ussd.exception.MwException;
import mfs.mw.ussd.mapper.MenuParser;
import mfs.mw.ussd.repository.MenuRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Menus from {@code ussd.menu} (spec 9.2): the version live for an MNO now (per-MNO activation
 * with a go-live time), and any version by id for a running session, which finishes on the
 * version it started with. Versions are immutable, so a parsed version is cached for good.
 */
@Service
public class MenuService implements MenuCatalog {

  private final MenuRepository menus;
  private final JsonMapper json;
  private final Clock clock;
  private final Map<String, Menu> cache = new ConcurrentHashMap<>();

  public MenuService(MenuRepository menus, JsonMapper json, Clock clock) {
    this.menus = menus;
    this.json = json;
    this.clock = clock;
  }

  @Override
  public Menu active(String mno) {
    MenuVersion v =
        menus.active(mno, clock.instant())
            .orElseThrow(() -> new MwException(ErrorCodes.SYS_NO_MENU, "No menu is live for this MNO"));
    return cached(v);
  }

  @Override
  public Menu version(String menuId, int version) {
    String key = menuId + ":" + version;
    Menu m = cache.get(key);
    if (m != null) {
      return m;
    }
    return cached(
        menus.find(menuId, version)
            .orElseThrow(() -> new MwException(ErrorCodes.SYS_NO_MENU, "Menu version not found")));
  }

  private Menu cached(MenuVersion v) {
    return cache.computeIfAbsent(
        v.menuId() + ":" + v.version(), k -> MenuValidator.requireValid(MenuParser.parse(json, v.tree())));
  }
}

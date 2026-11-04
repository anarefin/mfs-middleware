package mfs.mw.ussd.service;

import mfs.mw.ussd.dto.Menu;

/** Where the engine gets menus: the version live for an MNO, or a session's own version. */
public interface MenuCatalog {

  Menu active(String mno);

  Menu version(String menuId, int version);
}

package mfs.mw.ussd.entity;

/** One row of {@code ussd.menu}: a menu version and its JSON tree. */
public record MenuVersion(String menuId, int version, String tree) {}

package mfs.mw.ussd.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One numbered option of a MENU or CONFIRM node.
 *
 * @param switchLanguage when true, choosing it toggles the session language (bn and en)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MenuOption(String key, Text label, String next, boolean switchLanguage) {}

package mfs.mw.admin.api.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.admin.api.config.ContentProperties;
import mfs.mw.admin.api.dto.AdminRole;
import mfs.mw.admin.api.dto.ErrorDetail;
import mfs.mw.admin.api.dto.Menu;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.dto.Text;
import mfs.mw.admin.api.dto.UssdPreviewBody;
import mfs.mw.admin.api.dto.UssdPreviewView;
import mfs.mw.admin.api.exception.ErrorCodes;
import mfs.mw.admin.api.exception.ForbiddenException;
import mfs.mw.admin.api.exception.MwException;
import mfs.mw.admin.api.mapper.MenuParser;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /admin/v1/ussd/menus/preview} (ticket 133b, FRS ADM-06, CH-USSD-06): the draft tree
 * through {@link MenuValidator} (400 MW-VAL-040 with its problems, as a proposal), then {@link
 * UssdPreview#walk} at the MNO's screen limits. No state: nothing is stored, published or audited.
 */
@Service
public class UssdPreviewService {

  private final ContentProperties content;
  private final JsonMapper json;

  public UssdPreviewService(ContentProperties content, JsonMapper json) {
    this.content = content;
    this.json = json;
  }

  public UssdPreviewView preview(Operator operator, UssdPreviewBody body) {
    if (!operator.hasAny(AdminRole.CONTENT_PREVIEW)) {
      throw new ForbiddenException("Previewing a menu needs the ops-maker or ops-checker role");
    }
    ContentProperties.UssdMno mno = content.mnos().contains(body.mno()) ? content.ussd().get(body.mno()) : null;
    if (mno == null) {
      throw invalid(List.of(new ErrorDetail("mno", "unknown MNO, one of " + content.mnos())));
    }
    String menuId = body.menuId() == null ? "main" : body.menuId();
    Map<String, Object> tree = new LinkedHashMap<>(body.menu());
    tree.put("menuId", menuId);
    tree.put("version", 1);
    Menu menu;
    try {
      menu = MenuParser.parse(json, tree);
    } catch (JacksonException | IllegalArgumentException e) {
      throw invalid(List.of(new ErrorDetail("menu", "not a USSD menu tree")));
    }
    List<String> problems = MenuValidator.problems(menu);
    if (!problems.isEmpty()) {
      throw invalid(problems.stream().map(p -> new ErrorDetail("menu", p)).toList());
    }
    String language = body.language() == null ? Text.BN : body.language();
    UssdPreview.Outcome outcome = body.outcome() == null ? UssdPreview.Outcome.COMPLETED : UssdPreview.Outcome.valueOf(body.outcome());
    UssdPreview.ScreenLimits limits =
        new UssdPreview.ScreenLimits(mno.maxLengthGsm(), mno.maxLengthUcs2(), "TRUNCATE".equals(mno.overflow()));
    List<UssdPreviewView.Screen> screens = UssdPreview.walk(menu, limits, language, body.inputs(), outcome).stream()
        .map(s -> new UssdPreviewView.Screen(s.input(), s.screen(), s.page(), s.pages(), s.ended()))
        .toList();
    return new UssdPreviewView(menuId, body.mno(), mno.shortCode(), mno.rootMenuId(), language, screens);
  }

  private static MwException invalid(List<ErrorDetail> details) {
    return new MwException(ErrorCodes.VAL_CONTENT_INVALID, "The menu tree is not usable").withDetails(details);
  }
}

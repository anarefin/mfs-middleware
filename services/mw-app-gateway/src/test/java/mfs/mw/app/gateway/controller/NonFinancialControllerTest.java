package mfs.mw.app.gateway.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.time.Instant;
import java.util.List;
import mfs.mw.app.gateway.config.SecurityConfig;
import mfs.mw.app.gateway.dto.Favourite;
import mfs.mw.app.gateway.dto.FavouriteList;
import mfs.mw.app.gateway.dto.InboxMessage;
import mfs.mw.app.gateway.dto.SavedBiller;
import mfs.mw.app.gateway.dto.SavedBillerList;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.security.AppUserResolver;
import mfs.mw.app.gateway.service.CustomerInboxService;
import mfs.mw.app.gateway.service.PreferenceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Saved billers, favourites and the inbox (ticket 107a). Another wallet's data is never returned. */
@WebMvcTest(NonFinancialController.class)
@Import({SecurityConfig.class, AppUserResolver.class})
class NonFinancialControllerTest {

  static final Instant AT = Instant.parse("2026-11-20T08:00:00Z");
  static final String MINE = "01711000001";
  static final String OTHER = "01711000002";
  static final String ID = "11111111-1111-1111-1111-111111111111";

  private final MockMvcTester mvc;

  @MockitoBean PreferenceService prefs;
  @MockitoBean CustomerInboxService inbox;

  @Autowired
  NonFinancialControllerTest(MockMvcTester mvc) {
    this.mvc = mvc;
  }

  static RequestPostProcessor token(String msisdn) {
    return jwt().jwt(j -> j.claim("msisdn", msisdn).claim("role", "customer").claim("amr", List.of("pin")));
  }

  @Test
  void theOwnerSeesTheSavedAccountAndAnotherWalletDoesNot() {
    SavedBiller row = new SavedBiller(ID, "DESCO", "9876543210", "home", AT);
    when(prefs.billers(any())).thenAnswer(inv -> MINE.equals(inv.getArgument(0))
        ? new SavedBillerList(List.of(row))
        : new SavedBillerList(List.of()));
    when(prefs.saveBiller(eq(MINE), eq("DESCO"), eq("9876543210"), eq("home"))).thenReturn(row);

    MvcTestResult created = mvc.post().uri("/app/v1/saved-billers").with(token(MINE))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"billerCode\":\"DESCO\",\"accountNo\":\"9876543210\",\"nickname\":\"home\"}")
        .exchange();
    assertThat(created).hasStatus(201);
    assertThat(created).bodyJson().extractingPath("$.accountNo").isEqualTo("9876543210");

    MvcTestResult mine = mvc.get().uri("/app/v1/saved-billers").with(token(MINE)).exchange();
    assertThat(mine).hasStatusOk().bodyJson().extractingPath("$.billers[0].accountNo").isEqualTo("9876543210");

    MvcTestResult other = mvc.get().uri("/app/v1/saved-billers").with(token(OTHER)).exchange();
    assertThat(other).hasStatusOk().bodyJson().extractingPath("$.billers").isEmpty();
    verify(prefs).billers(OTHER);
  }

  @Test
  void deletingAnotherWalletsBillerIs404() {
    doThrow(new MwException(ErrorCodes.VAL_NOT_FOUND, "Not found").withStatus(HttpStatus.NOT_FOUND))
        .when(prefs).deleteBiller(OTHER, ID);
    MvcTestResult r = mvc.delete().uri("/app/v1/saved-billers/{id}", ID).with(token(OTHER)).exchange();
    assertThat(r).hasStatus(404).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-003");
    verify(prefs, never()).deleteBiller(eq(MINE), anyString());
  }

  @Test
  void aFavouriteIsStoredForTheTokenWalletInCanonicalForm() {
    Favourite row = new Favourite(ID, "01711000099", "Amma", AT);
    when(prefs.saveFavourite(MINE, "01711000099", "Amma")).thenReturn(row);
    when(prefs.favourites(any())).thenAnswer(inv -> MINE.equals(inv.getArgument(0))
        ? new FavouriteList(List.of(row))
        : new FavouriteList(List.of()));

    MvcTestResult created = mvc.post().uri("/app/v1/favourites").with(token(MINE))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"msisdn\":\"8801711000099\",\"label\":\"Amma\"}")
        .exchange();
    assertThat(created).hasStatus(201);
    assertThat(created).bodyJson().extractingPath("$.msisdn").isEqualTo("01711000099");
    verify(prefs).saveFavourite(MINE, "01711000099", "Amma");

    MvcTestResult other = mvc.get().uri("/app/v1/favourites").with(token(OTHER)).exchange();
    assertThat(other).hasStatusOk().bodyJson().extractingPath("$.favourites").isEmpty();
  }

  @Test
  void aBadFavouriteNumberIs400AndNothingIsSaved() {
    MvcTestResult r = mvc.post().uri("/app/v1/favourites").with(token(MINE))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"msisdn\":\"12345\"}")
        .exchange();
    assertThat(r).hasStatus(400).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-001");
    verify(prefs, never()).saveFavourite(anyString(), anyString(), any());
  }

  @Test
  void theInboxIsTheTokenWalletAndAnotherIdIs404() {
    InboxMessage item = new InboxMessage("m-1", "SMS", "txn-1", "0171****001", "TXN", "You sent Tk 10.00", false, AT);
    when(inbox.page(eq(MINE), eq(20), eq(0))).thenReturn(new InboxMessage.Page(List.of(item), 20, 0));
    doThrow(new MwException(ErrorCodes.VAL_NOT_FOUND, "Not found").withStatus(HttpStatus.NOT_FOUND))
        .when(inbox).markRead(eq(MINE), eq("missing"));

    MvcTestResult page = mvc.get().uri("/app/v1/notifications").with(token(MINE)).exchange();
    assertThat(page).hasStatusOk().bodyJson().extractingPath("$.items[0].body").isEqualTo("You sent Tk 10.00");
    assertThat(page).bodyJson().extractingPath("$.items[0].msisdn").isEqualTo("0171****001");
    verify(inbox).page(MINE, 20, 0);
    verify(inbox, never()).page(eq(OTHER), anyInt(), anyInt());

    MvcTestResult missing = mvc.post().uri("/app/v1/notifications/{id}/read", "missing").with(token(MINE)).exchange();
    assertThat(missing).hasStatus(404).bodyJson().extractingPath("$.code").isEqualTo("MW-VAL-003");
  }

  @Test
  void noTokenIs401() {
    assertThat(mvc.get().uri("/app/v1/saved-billers").exchange()).hasStatus(401);
    assertThat(mvc.get().uri("/app/v1/favourites").exchange()).hasStatus(401);
    assertThat(mvc.get().uri("/app/v1/notifications").exchange()).hasStatus(401);
  }
}

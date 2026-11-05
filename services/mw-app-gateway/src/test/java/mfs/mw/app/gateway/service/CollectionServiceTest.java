package mfs.mw.app.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import mfs.mw.app.gateway.dto.Amount;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.CollectionList;
import mfs.mw.app.gateway.dto.Party;
import mfs.mw.app.gateway.dto.TransactionView;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.mapper.CollectionMapper;
import mfs.mw.app.gateway.repository.AppTransactionRepository;
import org.junit.jupiter.api.Test;

/** The merchant collections view (ticket 30): merchant role only, own wallet only, payer masked. */
class CollectionServiceTest {

  static final AppUser MERCHANT = new AppUser("01911000001", "MERCHANT_APP", "MERCHANT");
  static final AppUser CUSTOMER = new AppUser("01711000001", "CUSTOMER_APP", "CUSTOMER");
  static final AppUser AGENT = new AppUser("01811000001", "AGENT_APP", "AGENT");
  static final Instant T0 = Instant.parse("2026-11-04T06:00:00Z");

  final AppTransactionRepository repo = mock(AppTransactionRepository.class);
  final CollectionService service = new CollectionService(repo, new CollectionMapper());

  @Test
  void onlyTheMerchantRoleMayListCollections() {
    for (AppUser user : List.of(CUSTOMER, AGENT)) {
      assertThatThrownBy(() -> service.collections(user, null))
          .as(user.walletType())
          .isInstanceOfSatisfying(
              MwException.class,
              e -> {
                assertThat(e.code()).isEqualTo("MW-AUTH-002");
                assertThat(e.status().value()).isEqualTo(403);
              });
    }
    verifyNoInteractions(repo);
  }

  @Test
  void theMerchantSeesThePaymentsToItsOwnWalletWithThePayerMasked() {
    TransactionView paid =
        new TransactionView(
            "019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0d", "COMPLETED", "MERCHANT_PAY",
            Party.wallet("01711000789", "CUSTOMER"), Party.wallet("01911000001", "MERCHANT"),
            new Amount(25_000L, "BDT"), null, "CR-1", null, T0, T0.plusSeconds(1));
    when(repo.collections("01911000001", 50)).thenReturn(List.of(paid));

    CollectionList list = service.collections(MERCHANT, null);

    assertThat(list.collections()).singleElement().satisfies(c -> {
      assertThat(c.txnId()).isEqualTo("019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0d");
      assertThat(c.status()).isEqualTo("COMPLETED");
      assertThat(c.amount().valueMinor()).isEqualTo(25_000L);
      assertThat(c.payer()).isEqualTo("0171****789");
      assertThat(c.coreRef()).isEqualTo("CR-1");
      assertThat(c.requestedAt()).isEqualTo(T0);
    });
  }

  @Test
  void theLimitIsPassedOn() {
    when(repo.collections("01911000001", 7)).thenReturn(List.of());
    assertThat(service.collections(MERCHANT, 7).collections()).isEmpty();
    verify(repo).collections("01911000001", 7);
  }
}

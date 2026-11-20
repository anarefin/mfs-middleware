package mfs.mw.app.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mfs.mw.app.gateway.client.AuthClient;
import mfs.mw.app.gateway.config.AppGatewayProperties;
import mfs.mw.app.gateway.dto.Amount;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.EncryptedPin;
import mfs.mw.app.gateway.dto.TransactionRequest;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.mapper.RequestMapper;
import mfs.mw.app.gateway.messaging.EventPublisher;
import mfs.mw.app.gateway.repository.AppTransactionRepository;
import org.junit.jupiter.api.Test;

/**
 * Each app type may call only its groups (ticket 106, FRS CH-APP-06). A customer token on {@code
 * b2b} is 403 MW-AUTH-002. B2B is the distributor, the DSO and the agent (the return). Disbursement
 * is the partner app only.
 */
class RoleScopingTest {

  static final Map<String, Set<String>> ALLOWED = Map.of(
      "CUSTOMER", Set.of("send-money", "cash-out", "payment", "bill-pay", "top-up", "add-money", "transfer-to-bank"),
      "AGENT", Set.of("cash-in", "b2b"),
      "MERCHANT", Set.of(),
      "DISTRIBUTOR", Set.of("b2b"),
      "DSO", Set.of("b2b"),
      "PARTNER", Set.of("disburse"));

  final TransactionService service = new TransactionService(
      mock(EventPublisher.class), new ReplyWaiters(), new RequestMapper(), mock(AppTransactionRepository.class),
      new AppGatewayProperties(
          Duration.ofMillis(50), "gw-unit", "mw.txn.request.v1", "mw.txn.status.v1",
          List.of("P2P", "CASH_IN", "CASH_OUT", "MERCHANT_PAY", "TOPUP", "BILL_PAY", "ADD_MONEY_BANK", "W2B",
              "B2B_TRANSFER", "BULK_DISBURSE"),
          List.of("reference"),
          new AppGatewayProperties.Jwt("http://mw-auth/jwks", "mw-auth", "mw-app"),
          Map.of(
              "P2P", List.of("CUSTOMER"),
              "CASH_IN", List.of("AGENT"),
              "CASH_OUT", List.of("CUSTOMER"),
              "MERCHANT_PAY", List.of("CUSTOMER"),
              "TOPUP", List.of("CUSTOMER"),
              "BILL_PAY", List.of("CUSTOMER"),
              "ADD_MONEY_BANK", List.of("CUSTOMER"),
              "W2B", List.of("CUSTOMER"),
              "B2B_TRANSFER", List.of("DISTRIBUTOR", "DSO", "AGENT"),
              "BULK_DISBURSE", List.of("PARTNER"))),
      Clock.systemUTC(), mock(BillerService.class), mock(AuthClient.class), mock(IdempotentReplay.class),
      mock(LinkedAccountService.class));

  @Test
  void eachAppTypeCanCallOnlyItsGroups() {
    for (var role : ALLOWED.entrySet()) {
      AppUser user = user(role.getKey());
      for (var group : PreviewService.GROUPS.entrySet()) {
        TransactionRequest body = new TransactionRequest(
            group.getValue(), new TransactionRequest.CounterParty(null, "01719999999"),
            new Amount(1_000L, "BDT"), new EncryptedPin("k", "Yw=="), null, null);
        if (role.getValue().contains(group.getKey())) {
          try {
            service.check(user, body);
          } catch (MwException e) {
            assertThat(e.code()).as(role.getKey() + " " + group.getKey()).isNotEqualTo("MW-AUTH-002");
          }
        } else {
          assertThatThrownBy(() -> service.check(user, body))
              .as(role.getKey() + " " + group.getKey())
              .isInstanceOfSatisfying(MwException.class, e -> assertThat(e.code()).isEqualTo("MW-AUTH-002"));
        }
      }
    }
  }

  private static AppUser user(String role) {
    String msisdn = switch (role) {
      case "AGENT" -> "01812000001";
      case "MERCHANT" -> "01912000001";
      case "DISTRIBUTOR" -> "01612000001";
      case "DSO" -> "01512000001";
      case "PARTNER" -> "01412000001";
      default -> "01712000001";
    };
    return new AppUser(msisdn, role + "_APP", role);
  }
}

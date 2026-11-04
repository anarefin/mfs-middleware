package mfs.mw.app.gateway.service;

import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.CollectionList;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.mapper.CollectionMapper;
import mfs.mw.app.gateway.repository.AppTransactionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The merchant collections view (spec WP20, ticket 30): the MERCHANT_PAY payments to the token's
 * own merchant wallet that reached it. Merchant role only (403 MW-AUTH-002 otherwise); the wallet
 * comes from the token, never from the request, so a merchant cannot read another's collections.
 */
@Service
public class CollectionService {

  public static final int DEFAULT_LIMIT = 50;
  public static final int MAX_LIMIT = 100;

  private final AppTransactionRepository transactions;
  private final CollectionMapper mapper;

  public CollectionService(AppTransactionRepository transactions, CollectionMapper mapper) {
    this.transactions = transactions;
    this.mapper = mapper;
  }

  /** @param limit 1..{@link #MAX_LIMIT} (checked by the controller), null for the default */
  public CollectionList collections(AppUser user, Integer limit) {
    if (!"MERCHANT".equals(user.walletType())) {
      throw new MwException(ErrorCodes.AUTH_FORBIDDEN, "Only a merchant can see collections")
          .withStatus(HttpStatus.FORBIDDEN);
    }
    int rows = limit == null ? DEFAULT_LIMIT : limit;
    return new CollectionList(
        transactions.collections(user.msisdn(), rows).stream().map(mapper::toCollection).toList());
  }
}

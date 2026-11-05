package mfs.mw.app.gateway.mapper;

import mfs.mw.app.gateway.dto.MerchantCollection;
import mfs.mw.app.gateway.dto.TransactionView;
import mfs.mw.app.gateway.util.MaskingPolicy;
import mfs.mw.app.gateway.util.SensitiveType;
import org.springframework.stereotype.Component;

/** A MERCHANT_PAY view to what the merchant sees of it: the payer masked (18.3), no PIN, no fee. */
@Component
public class CollectionMapper {

  public MerchantCollection toCollection(TransactionView v) {
    String payer = v.debitParty() == null ? null : MaskingPolicy.mask(SensitiveType.MSISDN, v.debitParty().id());
    return new MerchantCollection(
        v.txnId(), v.status(), v.amount(), payer, v.coreRef(), v.requestedAt(), v.updatedAt());
  }
}

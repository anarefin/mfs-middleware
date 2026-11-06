// Port of merchant.feature. customer01 pays merchant01 (01911000001), no fee; merchant02 is the
// other merchant. The collections view is projected from mw.txn.status.v1, so positive checks poll.
export default [
  {
    name: '[merchant] a customer pays a merchant and the merchant sees the collection',
    run: async (MW, S) => {
      await S.note(['01711000001', '01911000001', 'FEE-POOL']);
      await S.txn('customer01', 'MERCHANT_PAY', '01911000001', 2500, '1234');
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.changedBy({ '01711000001': -2500, '01911000001': 2500, 'FEE-POOL': 0 });
      await S.appShows('COMPLETED');
      const id = S.txnId();
      const entry = r => r.status === 200 ? ((r.body || {}).collections || []).find(c => c.txnId === id) : undefined;
      const r = await MW.poll(() => MW.app.collections('merchant01'), x => !!entry(x), 20000, 700);
      const c = entry(r);
      S.check('merchant01 sees the transaction in its collections', !!c, String(r));
      S.eq('COMPLETED, payer masked as 0171****001, amount 2500', [c.status, c.payer, c.amount.valueMinor], ['COMPLETED', '0171****001', 2500]);
      const other = await MW.app.collections('merchant02');
      S.status(200, other);
      S.check('merchant02 does not see the transaction', !entry(other), String(other));
    },
  },
  {
    name: "[merchant] a payment the customer's balance cannot cover is rejected and moves no money",
    run: async (MW, S) => {
      await S.note(['01711000001', '01911000001', 'FEE-POOL']);
      await S.reject('ValidateTransaction', 'INSUFFICIENT_BALANCE');
      await S.txn('customer01', 'MERCHANT_PAY', '01911000001', 2500, '1234');
      S.status(422);
      S.code('MW-CORE-INSUFFICIENT_BALANCE');
      S.carriesTxnId();
      await S.noChange();
      await S.appShows('REJECTED');
      const id = S.txnId();
      const r = await MW.app.collections('merchant01');
      S.status(200, r);
      S.check('merchant01 does not see the transaction', !((r.body || {}).collections || []).some(c => c.txnId === id), String(r));
    },
  },
  {
    name: '[merchant] a merchant payment to a wallet that is not a merchant is refused by the core',
    run: async (MW, S) => {
      await S.note(['01711000001', '01911000001', 'FEE-POOL']);
      await S.txn('customer01', 'MERCHANT_PAY', '01711000002', 2500, '1234');
      S.status(422);
      S.code('MW-CORE-INVALID_COUNTERPARTY');
      await S.noChange();
    },
  },
  ...[['agent01', 'MERCHANT_PAY', '01911000001'], ['merchant01', 'MERCHANT_PAY', '01911000002'], ['merchant01', 'P2P', '01711000001']].map(([user, type, counterparty]) => ({
    name: `[merchant] ${user} may not initiate ${type}`,
    args: { user, type, counterparty },
    run: async (MW, S, a) => {
      await S.note(['01711000001', '01911000001', 'FEE-POOL']);
      await S.txn(a.user, a.type, a.counterparty, 2500, '1234');
      S.status(403);
      S.code('MW-AUTH-002');
      await S.noChange();
    },
  })),
  ...['customer01', 'agent01'].map(user => ({
    name: `[merchant] ${user} has no collections view`,
    args: { user },
    run: async (MW, S, a) => {
      S.record(await MW.app.collections(a.user));
      S.status(403);
      S.code('MW-AUTH-002');
    },
  })),
  {
    name: '[merchant] a merchant payment sent twice with the same idempotency key pays the merchant once',
    run: async (MW, S) => {
      await S.note(['01711000001', '01911000001', 'FEE-POOL']);
      await S.txn('customer01', 'MERCHANT_PAY', '01911000001', 2500, '1234');
      await S.repeat();
      S.status(200);
      S.sameTxnId();
      S.txnStatus('COMPLETED');
      await S.changedBy({ '01711000001': -2500, '01911000001': 2500, 'FEE-POOL': 0 });
    },
  },
  {
    name: '[merchant tsq] a merchant payment whose core reply is lost is found by QueryTransaction and completes',
    run: async (MW, S) => {
      await S.note(['01711000001', '01911000001', 'FEE-POOL']);
      await S.dropReply('PostTransfer');
      await S.txn('customer01', 'MERCHANT_PAY', '01911000001', 2500, '1234');
      S.status(202);
      await S.appShows('COMPLETED', 90);
      await S.changedBy({ '01711000001': -2500, '01911000001': 2500, 'FEE-POOL': 0 });
      const id = S.txnId();
      const r = await MW.poll(() => MW.app.collections('merchant01'), x => ((x.body || {}).collections || []).some(c => c.txnId === id), 20000, 700);
      const c = ((r.body || {}).collections || []).find(x => x.txnId === id);
      S.check('merchant01 sees the transaction, payer 0171****001', !!c && c.payer === '0171****001', String(r));
    },
  },
  {
    name: '[merchant tsq] a merchant payment whose core refusal is lost is found by QueryTransaction and fails',
    run: async (MW, S) => {
      await S.note(['01711000001', '01911000001', 'FEE-POOL']);
      await S.refusalLost('PostTransfer');
      await S.txn('customer01', 'MERCHANT_PAY', '01911000001', 2500, '1234');
      S.status(202);
      await S.appShows('FAILED', 90, 'MW-TMO-003');
      await S.noChange();
    },
  },
];

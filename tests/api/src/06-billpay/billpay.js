// Port of billpay.feature. Aggregator simulator (billagg): amount due = 100 taka + the bill number's
// last three digits; bills ending 404 unknown, ending 999 refused on payment; fraction .13 times out.
export default [
  {
    name: '[billpay] the customer sees the billers and fetches the amount due on a bill',
    run: async (MW, S) => {
      await S.note(['01711000001', 'SETTLEMENT', 'FEE-POOL']);
      const b = S.record(await MW.app.billers('customer01'));
      S.status(200);
      S.check('the billers include DESCO', ((b.body || {}).billers || []).some(x => x.code === 'DESCO'), String(b));
      const f = S.record(await MW.app.fetchBill('customer01', 'DESCO', '1234567'));
      S.status(200);
      S.eq('the amount due is 66700 poisha', f.body.amount && f.body.amount.valueMinor, 66700, String(f));
      await S.noChange();
    },
  },
  {
    name: '[billpay] an unknown bill number is a mapped aggregator error',
    run: async (MW, S) => {
      S.record(await MW.app.fetchBill('customer01', 'DESCO', '1234404'));
      S.status(502);
      S.code('MW-PTR-BILLAGG-BILL_NOT_FOUND');
    },
  },
  {
    name: '[billpay] an unknown biller is not found',
    run: async (MW, S) => {
      S.record(await MW.app.fetchBill('customer01', 'NOPE', '1234567'));
      S.status(404);
      S.code('MW-VAL-003');
    },
  },
  {
    name: '[billpay] a paid bill debits the payer once and leaves nothing held',
    run: async (MW, S) => {
      await S.note(['01711000001', 'SETTLEMENT', 'FEE-POOL']);
      await S.billPay('customer01', 'DESCO', '1234567', 66700, '1234');
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.changedBy({ '01711000001': -67000, 'SETTLEMENT': 66700, 'FEE-POOL': 300 });
      await S.noHold('01711000001');
      await S.appShows('COMPLETED');
    },
  },
  {
    name: '[billpay] a biller refusal releases the hold and moves no money',
    run: async (MW, S) => {
      await S.note(['01711000001', 'SETTLEMENT', 'FEE-POOL']);
      await S.billPay('customer01', 'DESCO', '1234999', 10000, '1234');
      S.status(502);
      S.code('MW-PTR-BILLAGG-PAYMENT_REFUSED');
      S.carriesTxnId();
      await S.noChange();
      await S.noHold('01711000001');
      await S.appShows('FAILED');
    },
  },
  {
    name: '[billpay] a bill payment without a biller is refused by the app API',
    run: async (MW, S) => {
      await S.note(['01711000001', 'SETTLEMENT', 'FEE-POOL']);
      await S.billPay('customer01', null, '1234567', 10000, '1234');
      S.status(400);
      S.code('MW-VAL-001');
      await S.noChange();
    },
  },
  {
    name: '[billpay tsq] an aggregator timeout is resolved by status inquiry and the hold is committed',
    run: async (MW, S) => {
      await S.note(['01711000001', 'SETTLEMENT', 'FEE-POOL']);
      await S.billPay('customer01', 'DESCO', '1234567', 10013, '1234');
      S.status(202);
      S.txnStatus('PROCESSING');
      await S.appShows('COMPLETED', 120);
      await S.changedBy({ '01711000001': -10313, 'SETTLEMENT': 10013, 'FEE-POOL': 300 });
      await S.noHold('01711000001');
    },
  },
  {
    name: '[billpay] a paid bill leaves an audit trail of the hold path',
    run: async (MW, S) => {
      await S.billPay('customer01', 'DESCO', '1234567', 5000, '1234');
      S.status(200);
      await S.auditStates('RECEIVED, VALIDATING, HOLDING, PARTNER_PENDING, COMMITTING, COMPLETED');
      await S.auditPartnerCall('mw-adapter-billpay');
    },
  },
  {
    name: '[billpay] a bill payment sent twice with the same idempotency key pays the bill once',
    run: async (MW, S) => {
      await S.note(['01711000001', 'SETTLEMENT', 'FEE-POOL']);
      await S.billPay('customer01', 'DESCO', '1234567', 10000, '1234');
      await S.repeat();
      S.status(200);
      S.sameTxnId();
      S.txnStatus('COMPLETED');
      await S.changedBy({ '01711000001': -10300, 'SETTLEMENT': 10000, 'FEE-POOL': 300 });
      await S.noHold('01711000001');
    },
  },
  {
    name: '[billpay tsq] an aggregator timeout the payment did not survive is resolved by status inquiry and released',
    run: async (MW, S) => {
      await S.note(['01711000001', 'SETTLEMENT', 'FEE-POOL']);
      await S.lateUnapplied('billagg');
      await S.billPay('customer01', 'DESCO', '1234567', 10000, '1234');
      S.status(202);
      S.txnStatus('PROCESSING');
      await S.normalOnceAnswered('billagg', '/billagg/v1/payments');
      await S.appShows('FAILED', 180, 'MW-TMO-003');
      await S.noChange();
      await S.noHold('01711000001');
    },
  },
];

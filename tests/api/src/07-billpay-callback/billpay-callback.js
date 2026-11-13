// Port of billpay-callback.feature. billagg in callback-only mode accepts every payment with 202
// PENDING and posts the signed outcome to /partner/billagg/callback/payments through APISIX.
export default [
  {
    name: "[billpay callback] a callback-only payment is completed by the aggregator's callback",
    run: async (MW, S) => {
      await S.note(['01711000001', 'SETTLEMENT', 'FEE-POOL']);
      S.after(() => MW.sim.reset('billagg'));
      await MW.callbacks.billaggCallbacks('ONCE');
      await S.billPay('customer01', 'DESCO', '1234567', 66700, '1234');
      await S.appShows('COMPLETED', 25);
      await S.changedBy({ '01711000001': -67000, 'SETTLEMENT': 66700, 'FEE-POOL': 300 });
      await S.noHold('01711000001');
      await S.auditStates('RECEIVED, VALIDATING, HOLDING, PARTNER_PENDING, COMMITTING, COMPLETED', true);
      await S.noLateCallback();
    },
  },
  {
    name: '[billpay callback] a refusal reported by callback releases the hold',
    run: async (MW, S) => {
      await S.note(['01711000001', 'SETTLEMENT', 'FEE-POOL']);
      S.after(() => MW.sim.reset('billagg'));
      await MW.callbacks.billaggCallbacks('ONCE');
      await S.billPay('customer01', 'DESCO', '1234999', 10000, '1234');
      await S.appShows('FAILED', 25, 'MW-PTR-BILLAGG-PAYMENT_REFUSED');
      await S.noChange();
      await S.noHold('01711000001');
    },
  },
  {
    name: '[billpay callback] a duplicate callback has no second effect',
    run: async (MW, S) => {
      await S.note(['01711000001', 'SETTLEMENT', 'FEE-POOL']);
      S.after(() => MW.sim.reset('billagg'));
      await MW.callbacks.billaggCallbacks('DUPLICATE');
      await S.billPay('customer01', 'DESCO', '1234567', 5100, '1234');
      await S.appShows('COMPLETED', 25);
      const target = await MW.sim.replay('billagg', MW.callbacks.reference(S.txnId()));
      S.eq('the replayed callback was acknowledged', target, 200);
      await S.appShows('COMPLETED');
      await S.changedBy({ '01711000001': -5400, 'SETTLEMENT': 5100, 'FEE-POOL': 300 });
      await S.auditStates('RECEIVED, VALIDATING, HOLDING, PARTNER_PENDING, COMMITTING, COMPLETED', true);
      await S.noLateCallback();
    },
  },
  {
    name: '[billpay callback] a stale PENDING callback after the outcome changes nothing',
    run: async (MW, S) => {
      await S.note(['01711000001', 'SETTLEMENT', 'FEE-POOL']);
      S.after(() => MW.sim.reset('billagg'));
      await MW.callbacks.billaggCallbacks('OUT_OF_ORDER');
      await S.billPay('customer01', 'DESCO', '1234567', 5200, '1234');
      await S.appShows('COMPLETED', 25);
      await S.auditCallback('partner.callback.late', 'SUCCESS', 'PENDING', 30);
      await S.appShows('COMPLETED');
      await S.changedBy({ '01711000001': -5500, 'SETTLEMENT': 5200, 'FEE-POOL': 300 });
    },
  },
  {
    // Cucumber sent the callback 90 s late; with the test timers (8 s partner deadline, first inquiry
    // 6 s later) the status inquiry resolves the payment within about 15 s, so 20 s late is enough.
    name: '[billpay callback tsq] a late callback after the status inquiry resolved the payment is recorded and changes nothing',
    run: async (MW, S) => {
      await S.note(['01711000001', 'SETTLEMENT', 'FEE-POOL']);
      S.after(() => MW.sim.reset('billagg'));
      await MW.callbacks.billaggCallbacks('LATE', MW.ocsLimit() <= 5000 ? 20000 : 90000);
      await S.billPay('customer01', 'DESCO', '1234567', 5300, '1234');
      await S.appShows('COMPLETED', 120);
      await S.auditCallback('partner.callback.late', 'SUCCESS', 'SUCCESS', 100);
      await S.appShows('COMPLETED');
      await S.changedBy({ '01711000001': -5600, 'SETTLEMENT': 5300, 'FEE-POOL': 300 });
      await S.noHold('01711000001');
    },
  },
  {
    name: '[billpay callback] a late callback contradicting the resolution is recorded as a conflict and changes nothing',
    run: async (MW, S) => {
      await S.note(['01711000001', 'SETTLEMENT', 'FEE-POOL']);
      S.after(() => MW.sim.reset('billagg'));
      await MW.callbacks.billaggCallbacks('ONCE');
      await S.billPay('customer01', 'DESCO', '1234567', 5400, '1234');
      await S.appShows('COMPLETED', 25);
      const cb = await MW.callbacks.send(MW.callbacks.body(MW.callbacks.reference(S.txnId()), 'FAILED', '3003'), MW.v('billaggSecret'));
      S.status(200, cb);
      await S.auditCallback('txn.outcome.conflict', 'FAILURE', 'FAILED', 30);
      await S.appShows('COMPLETED');
      await S.changedBy({ '01711000001': -5700, 'SETTLEMENT': 5400, 'FEE-POOL': 300 });
    },
  },
  {
    name: '[billpay callback] a callback with a bad signature is rejected and changes nothing',
    run: async (MW, S) => {
      await S.note(['01711000001', 'SETTLEMENT', 'FEE-POOL']);
      S.after(() => MW.sim.reset('billagg'));
      await MW.callbacks.billaggCallbacks('ONCE');
      await S.billPay('customer01', 'DESCO', '1234567', 5500, '1234');
      await S.appShows('COMPLETED', 25);
      const cb = await MW.callbacks.send(MW.callbacks.body(MW.callbacks.reference(S.txnId()), 'FAILED', '3003'), 'not-the-partner-secret');
      S.status(401, cb);
      S.code('MW-AUTH-001', cb);
      await S.noLateCallback();
      await S.appShows('COMPLETED');
    },
  },
];

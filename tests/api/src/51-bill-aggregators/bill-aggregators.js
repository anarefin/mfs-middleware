// Ticket 114. Seeded bills (services/mw-simulators/src/main/resources/seed/v1/bills/<aggregator>.csv):
// AMBERIT (sslcommerz) 1140000001 1250.50 UNPAID, 1140000003 PAID, CARNIVAL 1140000011 3200.00 UNPAID;
// NESCO (ekpay) 1140000001 1250.50; DU (agg3, SOAP) 1140000001 1250.50; METLIFE (agg4) 1140000002 480.00.
// The bills are stateful: each scenario that pays one starts from the party reset of the simulator.
// Tariff v1: a bill of 100..500000 poisha costs a 300 poisha fee (261 fee income, 39 VAT).
// Helpers are copied into each script; a module-level binding is not.
export const helpers = [
  function books() {
    return ['01711000001', 'SETTLEMENT', 'FEE_INCOME'];
  },
  function moved(amount) {
    return { '01711000001': -(amount + 300), 'SETTLEMENT': amount, 'FEE_INCOME': 261 };
  },
  async function journalSince(MW, party) {
    const rows = await MW.mockJournal(party);
    return rows.reduce((m, r) => Math.max(m, r.seq || 0), 0);
  },
  async function untilHealth(MW, S, partnerId, want) {
    const start = Date.now();
    let last;
    while (Date.now() - start < 8000) {
      last = await MW.http('GET', '/test/orchestrator/v1/partner-health/' + partnerId, { apikey: true });
      if (last.body && last.body.health === want) return last;
      await new Promise(r => setTimeout(r, 200));
    }
    S.eq('routing noted ' + partnerId + ' ' + want, last && last.body && last.body.health, want, last);
    return last;
  },
  async function driveInquiry(MW, S) {
    const r = await MW.http('POST', '/test/orchestrator/v1/inquiries', {
      apikey: true, json: { txnId: S.txnId() },
    });
    S.status(202, r);
  },
  async function duplicateCallbacks(MW, partner) {
    await MW.http('PUT', '/test/mock-control/' + partner + '/settings', {
      apikey: true,
      json: {
        callbackUrl: 'http://apisix:9080/partner/' + partner + '/callback/payments',
        callbackOnly: true,
      },
    });
    await MW.mockScenario(partner, null, 'DUPLICATE_CALLBACK', { nextN: 1 });
  },
];

export default [
  {
    name: '[billpay] every bill aggregator answers a seeded bill in its own wire format',
    run: async (MW, S) => {
      const cases = [['AMBERIT', '1140000001', null, 125050], ['NESCO', '1140000001', '202611', 125050],
        ['DU', '1140000001', '202611', 125050], ['METLIFE', '1140000002', null, 48000]];
      for (const [biller, bill, month, minor] of cases) {
        const f = S.record(await MW.app.fetchBill('customer01', biller, bill, month));
        S.status(200);
        S.eq(biller + ' amount due', f.body.amount && f.body.amount.valueMinor, minor, String(f));
      }
      await S.noChange();
    },
  },
  {
    name: '[billpay] a bill the aggregator holds as paid is a mapped aggregator error',
    run: async (MW, S) => {
      S.record(await MW.app.fetchBill('customer01', 'AMBERIT', '1140000003', null));
      S.status(502);
      S.code('MW-PTR-SSLCOMMERZ-BILL_ALREADY_PAID');
      S.record(await MW.app.fetchBill('customer01', 'DU', '1140000003', '202611'));
      S.status(502);
      S.code('MW-PTR-AGG3-BILL_ALREADY_PAID');
    },
  },
  {
    name: '[billpay] a bill paid through sslcommerz completes and is then fetched as paid',
    run: async (MW, S) => {
      await MW.mock.reset('sslcommerz');
      await S.note(['01711000001', 'SETTLEMENT', 'FEE_INCOME']);
      const f = S.record(await MW.app.fetchBill('customer01', 'CARNIVAL', '1140000011', null));
      S.status(200);
      S.eq('the amount due is 320000 poisha', f.body.amount && f.body.amount.valueMinor, 320000, String(f));
      await S.billPay('customer01', 'CARNIVAL', '1140000011', 320000, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.noHold('01711000001');
      S.record(await MW.app.fetchBill('customer01', 'CARNIVAL', '1140000011', null));
      S.status(502);
      S.code('MW-PTR-SSLCOMMERZ-BILL_ALREADY_PAID');
      await MW.mock.reset('sslcommerz');
    },
  },
  {
    name: '[billpay frs] T1 a sslcommerz bill payment completes and debits the payer once',
    run: async (MW, S) => {
      await MW.mock.reset('sslcommerz');
      await S.note(books());
      const f = S.record(await MW.app.fetchBill('customer01', 'CARNIVAL', '1140000011', null));
      S.status(200);
      S.eq('the amount due is 320000 poisha', f.body.amount && f.body.amount.valueMinor, 320000, String(f));
      await S.billPay('customer01', 'CARNIVAL', '1140000011', 320000, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.changedBy(moved(320000));
      await S.noHold('01711000001');
      await MW.mock.reset('sslcommerz');
    },
  },
  {
    name: '[billpay frs] T1 an agg3 SOAP bill payment completes and debits the payer once',
    run: async (MW, S) => {
      await MW.mock.reset('agg3');
      await S.note(books());
      const f = S.record(await MW.app.fetchBill('customer01', 'DU', '1140000001', '202611'));
      S.status(200);
      S.eq('the amount due is 125050 poisha', f.body.amount && f.body.amount.valueMinor, 125050, String(f));
      await S.billPay('customer01', 'DU', '1140000001', 125050, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.changedBy(moved(125050));
      await S.noHold('01711000001');
      await MW.mock.reset('agg3');
    },
  },
  {
    name: '[billpay frs] T2 a sslcommerz refusal shows the mapped aggregator error and moves nothing',
    run: async (MW, S) => {
      await MW.mock.reset('sslcommerz');
      S.after(() => MW.mock.reset('sslcommerz'));
      await MW.mockScenario('sslcommerz', 'pay', 'BUSINESS_ERROR', { code: 'E103', nextN: 1 });
      await S.note(books());
      await S.billPay('customer01', 'AMBERIT', '1140000002', 48000, MW.pin);
      S.status(502);
      S.code('MW-PTR-SSLCOMMERZ-PAYMENT_REFUSED');
      await S.noChange();
      await S.noHold('01711000001');
    },
  },
  {
    name: '[billpay frs] T2 an agg3 SOAP refusal shows the mapped aggregator error and moves nothing',
    run: async (MW, S) => {
      await MW.mock.reset('agg3');
      S.after(() => MW.mock.reset('agg3'));
      await MW.mockScenario('agg3', 'pay', 'BUSINESS_ERROR', { code: 'A3-03', nextN: 1 });
      await S.note(books());
      await S.billPay('customer01', 'DU', '1140000002', 48000, MW.pin);
      S.status(502);
      S.code('MW-PTR-AGG3-PAYMENT_REFUSED');
      await S.noChange();
      await S.noHold('01711000001');
    },
  },
  {
    name: '[billpay tsq frs] T3 a sslcommerz payment answered unknown is completed by status inquiry and debited once',
    run: async (MW, S) => {
      await MW.mock.reset('sslcommerz');
      S.after(() => MW.mock.reset('sslcommerz'));
      await MW.mockScenario('sslcommerz', 'pay', 'HTTP_5XX', { nextN: 1 });
      await S.note(books());
      await S.billPay('customer01', 'AMBERIT', '1140000001', 125050, MW.pin);
      S.status(202);
      S.txnStatus('PROCESSING');
      await driveInquiry(MW, S);
      const done = await MW.waitForState(S.txnId(), ['COMPLETED'], 20000);
      S.eq('the driven inquiry completed it', done.body && done.body.status, 'COMPLETED', String(done));
      await S.changedBy(moved(125050));
      await S.noHold('01711000001');
    },
  },
  {
    name: '[billpay tsq frs] T3 an agg3 payment answered unknown is completed by status inquiry and debited once',
    run: async (MW, S) => {
      await MW.mock.reset('agg3');
      S.after(() => MW.mock.reset('agg3'));
      await MW.mockScenario('agg3', 'pay', 'HTTP_5XX', { nextN: 1 });
      await S.note(books());
      await S.billPay('customer01', 'BUET', '1140000011', 320000, MW.pin);
      S.status(202);
      S.txnStatus('PROCESSING');
      await driveInquiry(MW, S);
      const done = await MW.waitForState(S.txnId(), ['COMPLETED'], 20000);
      S.eq('the driven inquiry completed it', done.body && done.body.status, 'COMPLETED', String(done));
      await S.changedBy(moved(320000));
      await S.noHold('01711000001');
    },
  },
  {
    name: '[billpay tsq frs] T4 a sslcommerz payment the status inquiry says was not found is released and moves nothing',
    run: async (MW, S) => {
      await MW.mock.reset('sslcommerz');
      S.after(() => MW.mock.reset('sslcommerz'));
      await MW.mockScenario('sslcommerz', 'pay', 'HTTP_5XX', { nextN: 1 });
      await MW.mockScenario('sslcommerz', 'status', 'HTTP_5XX', { httpStatus: 404, nextN: 1 });
      await S.note(books());
      await S.billPay('customer01', 'AMBERIT', '1140000002', 48000, MW.pin);
      S.status(202);
      S.txnStatus('PROCESSING');
      await driveInquiry(MW, S);
      const done = await MW.waitForState(S.txnId(), ['FAILED'], 20000);
      S.eq('the driven inquiry failed it', done.body && done.body.status, 'FAILED', String(done));
      await S.noChange();
      await S.noHold('01711000001');
    },
  },
  {
    name: '[billpay tsq frs] T4 an agg3 payment the status inquiry says was not found is released and moves nothing',
    run: async (MW, S) => {
      await MW.mock.reset('agg3');
      S.after(() => MW.mock.reset('agg3'));
      await MW.mockScenario('agg3', 'pay', 'HTTP_5XX', { nextN: 1 });
      await MW.mockScenario('agg3', 'status', 'HTTP_5XX', { httpStatus: 404, nextN: 1 });
      await S.note(books());
      await S.billPay('customer01', 'DU', '1140000002', 48000, MW.pin);
      S.status(202);
      S.txnStatus('PROCESSING');
      await driveInquiry(MW, S);
      const done = await MW.waitForState(S.txnId(), ['FAILED'], 20000);
      S.eq('the driven inquiry failed it', done.body && done.body.status, 'FAILED', String(done));
      await S.noChange();
      await S.noHold('01711000001');
    },
  },
  {
    name: '[billpay frs] T6 the same Idempotency-Key twice pays a sslcommerz bill once',
    run: async (MW, S) => {
      await MW.mock.reset('sslcommerz');
      await S.note(books());
      await S.billPay('customer01', 'AMBERIT', '1140000001', 125050, MW.pin);
      await S.repeat();
      S.status(200);
      S.sameTxnId();
      S.txnStatus('COMPLETED');
      await S.changedBy(moved(125050));
      await MW.mock.reset('sslcommerz');
    },
  },
  {
    name: '[billpay frs] T6 the same Idempotency-Key twice pays an agg3 bill once',
    run: async (MW, S) => {
      await MW.mock.reset('agg3');
      await S.note(books());
      await S.billPay('customer01', 'DU', '1140000001', 125050, MW.pin);
      await S.repeat();
      S.status(200);
      S.sameTxnId();
      S.txnStatus('COMPLETED');
      await S.changedBy(moved(125050));
      await MW.mock.reset('agg3');
    },
  },
  {
    name: '[billpay frs] T7 a duplicate sslcommerz callback completes the payment once',
    run: async (MW, S) => {
      await MW.mock.reset('sslcommerz');
      S.after(() => MW.mock.reset('sslcommerz'));
      await duplicateCallbacks(MW, 'sslcommerz');
      await S.note(books());
      await S.billPay('customer01', 'AMBERIT', '1140000002', 48000, MW.pin);
      await S.appShows('COMPLETED', 25);
      await S.changedBy(moved(48000));
      await S.noHold('01711000001');
      await S.appShows('COMPLETED');
    },
  },
  {
    name: '[billpay frs] T7 a duplicate agg3 callback completes the payment once',
    run: async (MW, S) => {
      await MW.mock.reset('agg3');
      S.after(() => MW.mock.reset('agg3'));
      await duplicateCallbacks(MW, 'agg3');
      await S.note(books());
      await S.billPay('customer01', 'DU', '1140000002', 48000, MW.pin);
      await S.appShows('COMPLETED', 25);
      await S.changedBy(moved(48000));
      await S.noHold('01711000001');
      await S.appShows('COMPLETED');
    },
  },
  {
    name: '[billpay frs] T8 a sslcommerz bill uses the billagg fallback when sslcommerz is DOWN',
    run: async (MW, S) => {
      await MW.mock.reset('sslcommerz');
      S.after(async () => {
        await MW.mock.reset('sslcommerz');
        await MW.mockSwitch('sslcommerz', 'UP');
      });
      const sslAt = await journalSince(MW, 'sslcommerz');
      const aggAt = await journalSince(MW, 'billagg');
      await MW.mockSwitch('sslcommerz', 'DOWN');
      await untilHealth(MW, S, 'sslcommerz', 'DOWN');
      await S.note(books());
      const f = S.record(await MW.app.fetchBill('customer01', 'AMBERIT', '1140000001', null));
      S.status(200);
      S.eq('the fallback computed 10100 poisha', f.body.amount && f.body.amount.valueMinor, 10100, String(f));
      await S.billPay('customer01', 'AMBERIT', '1140000001', 10100, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.changedBy(moved(10100));
      const ssl = (await MW.mockJournal('sslcommerz', { path: 'payments' })).filter(r => (r.seq || 0) > sslAt);
      const agg = (await MW.mockJournal('billagg', { path: 'payments' })).filter(r => (r.seq || 0) > aggAt);
      S.eq('sslcommerz was not paid', ssl.length, 0, ssl);
      S.eq('billagg was paid once', agg.length, 1, agg);
    },
  },
  {
    name: '[billpay frs] T8 an agg3 bill uses the ekpay fallback when agg3 is DOWN',
    run: async (MW, S) => {
      await MW.mock.reset('agg3');
      await MW.mock.reset('ekpay');
      S.after(async () => {
        await MW.mock.reset('agg3');
        await MW.mockSwitch('agg3', 'UP');
        await MW.mock.reset('ekpay');
      });
      const soapAt = await journalSince(MW, 'agg3');
      const ekAt = await journalSince(MW, 'ekpay');
      await MW.mockSwitch('agg3', 'DOWN');
      await untilHealth(MW, S, 'agg3', 'DOWN');
      await S.note(books());
      const f = S.record(await MW.app.fetchBill('customer01', 'DU', '1140000001', '202611'));
      S.status(200);
      S.eq('ekpay has the same 125050 poisha bill', f.body.amount && f.body.amount.valueMinor, 125050, String(f));
      await S.billPay('customer01', 'DU', '1140000001', 125050, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.changedBy(moved(125050));
      const soap = (await MW.mockJournal('agg3', { path: 'v1/soap', contains: 'PayBill' })).filter(r => (r.seq || 0) > soapAt);
      const ek = (await MW.mockJournal('ekpay', { path: 'payments' })).filter(r => (r.seq || 0) > ekAt);
      S.eq('agg3 was not paid', soap.length, 0, soap);
      S.eq('ekpay was paid once', ek.length, 1, ek);
    },
  },
  {
    name: '[billpay frs] T9 a limit is refused before any bill payment and an insufficient balance moves nothing',
    run: async (MW, S) => {
      await MW.mock.reset('sslcommerz');
      await S.note(books());
      const since = await journalSince(MW, 'sslcommerz');
      const over = S.record(await MW.app.preview('customer01', 'bill-pay', {
        creditParty: { type: 'BILLER_ACCOUNT', id: '1140000001' },
        partner: { billerCode: 'AMBERIT' },
        amount: { valueMinor: 600000, currency: 'BDT' },
      }));
      S.status(422, over);
      S.code('MW-CORE-LIMIT_EXCEEDED_PER_TXN', over);
      const pays = (await MW.mockJournal('sslcommerz', { path: 'payments' })).filter(r => (r.seq || 0) > since);
      S.eq('the limit stopped it before a payment', pays.length, 0, pays);
      await S.noChange();

      const payer = '01711000001';
      const was = await MW.coreBalance(payer);
      S.after(async () => {
        await MW.http('PUT', '/test/core-mock/v1/wallets/' + payer, { apikey: true, json: {
          type: 'CUSTOMER', openingBalanceMinor: was.balanceMinor, balanceMinor: was.availableMinor,
        } });
      });
      const set = await MW.http('PUT', '/test/core-mock/v1/wallets/' + payer, { apikey: true, json: {
        type: 'CUSTOMER', openingBalanceMinor: was.balanceMinor, balanceMinor: 100,
      } });
      S.status(200, set);
      await S.note(books());
      await S.billPay('customer01', 'AMBERIT', '1140000001', 125050, MW.pin);
      S.status(422);
      S.code('MW-CORE-INSUFFICIENT_BALANCE');
      await S.noChange();
      await MW.mock.reset('sslcommerz');
    },
  },
  {
    name: '[billpay frs] T10 a sslcommerz payment keeps the ledger identity; mid-saga restart is scripts/chaos.sh',
    run: async (MW, S) => {
      await MW.mock.reset('sslcommerz');
      await S.note(['01711000001', 'SETTLEMENT', 'FEE_INCOME', 'VAT_PAYABLE', 'COMMISSION_PAYABLE']);
      await S.billPay('customer01', 'CARNIVAL', '1140000011', 320000, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.changedBy({
        '01711000001': -320300, 'SETTLEMENT': 320000, 'FEE_INCOME': 261, 'VAT_PAYABLE': 39, 'COMMISSION_PAYABLE': 0,
      });
      const d = await S.deltas();
      S.eq('debits equal credits', -d['01711000001'], d.SETTLEMENT + d.FEE_INCOME + d.VAT_PAYABLE + d.COMMISSION_PAYABLE);
      await MW.mock.reset('sslcommerz');
    },
  },
  {
    name: '[billpay reversal frs] a second reverse of a completed sslcommerz payment reverses the aggregator once',
    run: async (MW, S) => {
      await MW.mock.reset('sslcommerz');
      S.after(() => MW.mock.reset('sslcommerz'));
      await S.note(books());
      await S.billPay('customer01', 'AMBERIT', '1140000002', 48000, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      const at = await journalSince(MW, 'sslcommerz');
      await MW.testApi.reverse(S.txnId());
      await S.appShows('REVERSED', 30);
      await MW.testApi.reverse(S.txnId());
      await S.appShows('REVERSED', 30);
      const reverses = (await MW.mockJournal('sslcommerz', { path: 'reverse' })).filter(r => (r.seq || 0) > at);
      S.eq('the aggregator was reversed once', reverses.length, 1, reverses);
      await S.noChange();
    },
  },
];

// Ticket 112c. TOPUP on the operator OCS profiles. Folder 05 keeps the legacy operator id gp.
// Helpers are copied into each script; a module-level binding is not.
export const helpers = [
  function books() {
    return ['01711000001', 'SETTLEMENT', 'FEE_INCOME', 'VAT_PAYABLE', 'COMMISSION_PAYABLE'];
  },
  function moved() {
    return { '01711000001': -2200, 'SETTLEMENT': 2000, 'FEE_INCOME': 174, 'VAT_PAYABLE': 26, 'COMMISSION_PAYABLE': 0 };
  },
  async function prepare(MW) {
    await MW.mockReset('ocs-gp');
    await MW.mockReset('ocs-robi');
    await MW.mockReset('ocs');
  },
  async function journalSince(MW, party) {
    const rows = await MW.mockJournal(party);
    return rows.reduce((m, r) => Math.max(m, r.seq || 0), 0);
  },
  async function driveInquiry(MW, S) {
    const r = await MW.http('POST', '/test/orchestrator/v1/inquiries', {
      apikey: true, json: { txnId: S.txnId() },
    });
    S.status(202, r);
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
];

export default [
  {
    name: '[topup frs] T1 a GP top-up on gp-ocs completes with the seed tariff fee, VAT and commission and the SMS is sent',
    run: async (MW, S) => {
      await prepare(MW);
      const since = await journalSince(MW, 'ocs-gp');
      await S.note(books());
      await S.previewTopup('customer01', 'gp-ocs', 'PREPAID', '01711000002', 2000, MW.pin);
      const q = S.quote;
      S.eq('fee 200 poisha (tariff v1)', q.body && q.body.fee && q.body.fee.valueMinor, 200, String(q));
      S.eq('VAT 26 poisha (15% inside the fee)', q.body && q.body.vat && q.body.vat.valueMinor, 26, String(q));
      S.eq('commission 0', q.body && q.body.commission && q.body.commission.valueMinor, 0, String(q));
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.changedBy(moved());
      await S.sms('01711000001', '0171****002 নম্বরে ৳20.00 রিচার্জ সফল হয়েছে। TrxID {txnId}');
      const j = await MW.mockJournal('ocs-gp', { path: 'recharge' });
      S.check('gp-ocs applied the recharge', j.some(r => (r.seq || 0) > since), j);
    },
  },
  {
    name: '[topup frs] T1 a Robi top-up on robi-ocs completes and debits the payer once',
    run: async (MW, S) => {
      await prepare(MW);
      const since = await journalSince(MW, 'ocs-robi');
      await S.note(books());
      await S.previewTopup('customer01', 'robi-ocs', 'PREPAID', '01811000002', 2000, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.changedBy(moved());
      const j = await MW.mockJournal('ocs-robi', { path: 'topup' });
      S.check('robi-ocs applied the recharge', j.some(r => (r.seq || 0) > since), j);
    },
  },
  {
    name: '[topup frs] T2 a GP subscriber-barred refusal shows the English and Bangla text and moves nothing',
    run: async (MW, S) => {
      await prepare(MW);
      S.after(() => MW.mockReset('ocs-gp'));
      await MW.mockScenario('ocs-gp', 'topup', 'BUSINESS_ERROR', { code: 'GP1001', nextN: 1 });
      await S.note(books());
      await S.previewTopup('customer01', 'gp-ocs', 'PREPAID', '01711000002', 2000, MW.pin);
      S.status(502);
      S.code('MW-PTR-GP_OCS-SUBSCRIBER_BARRED');
      S.eq('english text', S.last().body && S.last().body.message, 'Subscriber barred', String(S.last()));
      S.eq('bangla text', S.last().body && S.last().body.messageBn, 'গ্রাহক নম্বরটি নিষিদ্ধ', String(S.last()));
      await S.noChange();
    },
  },
  {
    name: '[topup frs] T2 a Robi barred refusal shows the English and Bangla text and moves nothing',
    run: async (MW, S) => {
      await prepare(MW);
      S.after(() => MW.mockReset('ocs-robi'));
      await MW.mockScenario('ocs-robi', 'topup', 'BUSINESS_ERROR', { code: 'RB77', nextN: 1 });
      await S.note(books());
      await S.previewTopup('customer01', 'robi-ocs', 'PREPAID', '01811000002', 2000, MW.pin);
      S.status(502);
      S.code('MW-PTR-ROBI_OCS-BARRED');
      S.eq('english text', S.last().body && S.last().body.message, 'Barred', String(S.last()));
      S.eq('bangla text', S.last().body && S.last().body.messageBn, 'নম্বরটি নিষিদ্ধ', String(S.last()));
      await S.noChange();
    },
  },
  {
    name: '[topup tsq frs] T3 a GP top-up that the OCS answers unknown is completed by a driven status inquiry and debited once',
    run: async (MW, S) => {
      await prepare(MW);
      S.after(() => MW.mockReset('ocs-gp'));
      await S.note(books());
      await MW.mockScenario('ocs-gp', 'topup', 'HTTP_5XX', { nextN: 1 });
      await S.previewTopup('customer01', 'gp-ocs', 'PREPAID', '01711000002', 2000, MW.pin);
      S.status(202);
      S.txnStatus('PROCESSING');
      await driveInquiry(MW, S);
      const done = await MW.waitForState(S.txnId(), ['COMPLETED'], 20000);
      S.eq('the driven inquiry completed it', done.body && done.body.status, 'COMPLETED', String(done));
      await S.changedBy(moved());
    },
  },
  {
    name: '[topup tsq frs] T4 a GP top-up the status inquiry says failed is released and moves nothing',
    run: async (MW, S) => {
      await prepare(MW);
      S.after(() => MW.mockReset('ocs-gp'));
      await S.note(books());
      await MW.mockScenario('ocs-gp', 'topup', 'HTTP_5XX', { nextN: 1 });
      await MW.mockScenario('ocs-gp', 'status', 'BUSINESS_ERROR', { code: 'GP1001', nextN: 1 });
      await S.previewTopup('customer01', 'gp-ocs', 'PREPAID', '01711000002', 2000, MW.pin);
      S.status(202);
      S.txnStatus('PROCESSING');
      await driveInquiry(MW, S);
      const done = await MW.waitForState(S.txnId(), ['FAILED'], 20000);
      S.eq('the driven inquiry failed it', done.body && done.body.status, 'FAILED', String(done));
      await S.noChange();
    },
  },
  {
    name: '[topup frs] T6 the same Idempotency-Key twice tops up once and returns the same txnId',
    run: async (MW, S) => {
      await prepare(MW);
      await S.note(books());
      await S.previewTopup('customer01', 'gp-ocs', 'PREPAID', '01711000002', 2000, MW.pin);
      await S.repeat();
      S.status(200);
      S.sameTxnId();
      S.txnStatus('COMPLETED');
      await S.changedBy(moved());
    },
  },
  {
    name: '[topup frs] T7 a duplicate core commit of a top-up posts the money once',
    run: async (MW, S) => {
      await prepare(MW);
      S.after(() => MW.mockReset('core'));
      await S.note(books());
      await MW.mockScenario('core', 'commit', 'DUPLICATE_CALLBACK', { nextN: 1 });
      await S.previewTopup('customer01', 'gp-ocs', 'PREPAID', '01711000002', 2000, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.changedBy(moved());
    },
  },
  {
    name: '[topup frs] T8 a GP top-up uses the ocs fallback when gp-ocs is DOWN',
    run: async (MW, S) => {
      await prepare(MW);
      S.after(() => MW.mockReset('ocs-gp'));
      await S.note(books());
      const gpAt = await journalSince(MW, 'ocs-gp');
      const ocsAt = await journalSince(MW, 'ocs');
      await MW.mockSwitch('ocs-gp', 'DOWN');
      await untilHealth(MW, S, 'gp-ocs', 'DOWN');
      await S.previewTopup('customer01', 'gp-ocs', 'PREPAID', '01711000002', 2000, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.changedBy(moved());
      const gp = (await MW.mockJournal('ocs-gp', { path: 'recharge' })).filter(r => (r.seq || 0) > gpAt);
      const ocs = (await MW.mockJournal('ocs', { path: 'topup' })).filter(r => (r.seq || 0) > ocsAt);
      S.eq('gp-ocs was not called', gp.length, 0, gp);
      S.eq('the legacy ocs fallback recharged once', ocs.length, 1, ocs);
    },
  },
  {
    name: '[topup frs] T9 a limit is refused before any recharge and an insufficient balance moves nothing',
    run: async (MW, S) => {
      await prepare(MW);
      await S.note(books());
      const since = await journalSince(MW, 'ocs-gp');
      const over = S.record(await MW.app.preview('customer01', 'top-up', {
        creditParty: { type: 'MSISDN', id: '01711000002' },
        partner: { partnerId: 'gp-ocs', productCode: 'PREPAID' },
        amount: { valueMinor: 600000, currency: 'BDT' },
      }));
      S.status(422, over);
      S.code('MW-CORE-LIMIT_EXCEEDED_PER_TXN', over);
      S.eq('english limit text', over.body && over.body.message, 'The amount is above the limit for one transaction', String(over));
      S.eq('bangla limit text', over.body && over.body.messageBn, 'লেনদেনের পরিমাণ একক লেনদেনের সীমা ছাড়িয়ে গেছে', String(over));
      const recharges = (await MW.mockJournal('ocs-gp', { path: 'recharge' })).filter(r => (r.seq || 0) > since);
      S.eq('the limit stopped it before a recharge', recharges.length, 0, recharges);
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
      await S.previewTopup('customer01', 'gp-ocs', 'PREPAID', '01711000002', 2000, MW.pin);
      S.status(422);
      S.code('MW-CORE-INSUFFICIENT_BALANCE');
      S.eq('english balance text', S.last().body && S.last().body.message, 'Insufficient balance', String(S.last()));
      S.eq('bangla balance text', S.last().body && S.last().body.messageBn, 'অপর্যাপ্ত ব্যালেন্স', String(S.last()));
      await S.noChange();
    },
  },
  {
    name: '[topup frs] T10 a top-up keeps the ledger identity; mid-saga restart is scripts/chaos.sh',
    run: async (MW, S) => {
      await prepare(MW);
      await S.note(books());
      await S.previewTopup('customer01', 'gp-ocs', 'PREPAID', '01711000002', 2000, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.changedBy(moved());
      const d = await S.deltas();
      S.eq('debits equal credits', -d['01711000001'], d.SETTLEMENT + d.FEE_INCOME + d.VAT_PAYABLE + d.COMMISSION_PAYABLE);
    },
  },
  {
    name: '[topup reversal frs] a second reverse of a completed gp-ocs recharge returns the first outcome',
    run: async (MW, S) => {
      await prepare(MW);
      await S.note(books());
      await S.previewTopup('customer01', 'gp-ocs', 'PREPAID', '01711000002', 2000, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      const at = await journalSince(MW, 'ocs-gp');
      await MW.testApi.reverse(S.txnId());
      await S.appShows('REVERSED', 30);
      await MW.testApi.reverse(S.txnId());
      await S.appShows('REVERSED', 30);
      const reverses = (await MW.mockJournal('ocs-gp', { path: 'reverse' })).filter(r => (r.seq || 0) > at);
      S.eq('the operator was reversed once', reverses.length, 1, reverses);
      await S.noChange();
    },
  },
];

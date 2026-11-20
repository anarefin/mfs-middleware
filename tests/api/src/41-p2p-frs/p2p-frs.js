// Ticket 104. P2P through the app, the core standing in for the external party.
// prepare is the folder's pre-request reset: scenarios and the switch, not the ledger.
export const helpers = [
  async function prepare(MW) {
    await MW.mockReset('core');
  },
  async function journalSince(MW, party) {
    const rows = await MW.mockJournal(party);
    return rows.reduce((m, r) => Math.max(m, r.seq || 0), 0);
  },
];

export default [
  {
    name: '[p2p frs] T1 a P2P completes with the seed tariff fee, VAT and commission and the SMS is in the SMSC journal',
    run: async (MW, S) => {
      await prepare(MW);
      const since = await journalSince(MW, 'smsc');
      await S.note(['01711000001', '01711000002', 'FEE_INCOME', 'VAT_PAYABLE', 'COMMISSION_PAYABLE']);
      const payer = await MW.coreBalance('01711000001');
      await S.previewP2p('customer01', '01711000002', 1000, MW.pin);
      const q = S.quote;
      S.eq('preview HTTP 200', q.status, 200, String(q));
      S.eq('fee 500 poisha (tariff v1)', q.body.fee && q.body.fee.valueMinor, 500, String(q));
      S.eq('VAT 65 poisha (15% inside the fee)', q.body.vat && q.body.vat.valueMinor, 65, String(q));
      S.eq('commission 0', q.body.commission && q.body.commission.valueMinor, 0, String(q));
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.changedBy({ '01711000001': -1500, '01711000002': 1000, 'FEE_INCOME': 435, 'VAT_PAYABLE': 65, 'COMMISSION_PAYABLE': 0 });
      const after = await MW.coreBalance('01711000001');
      S.eq('payer available balance fell by amount plus fee', after.availableMinor, payer.availableMinor - 1500, after);
      await S.sms('01711000001', 'আপনি 0171****002 নম্বরে ৳10.00 পাঠিয়েছেন। ফি ৳5.00। TrxID {txnId}');
      const j = await MW.mockJournal('smsc', { path: 'submit_sm' });
      S.check('the SMSC journal recorded the submit to the sender', j.some(r => (r.seq || 0) > since && r.headers && r.headers.destination === '01711000001'), j);
    },
  },
  {
    name: '[p2p frs] T2 a core business rejection shows the English and Bangla text and moves nothing',
    run: async (MW, S) => {
      await prepare(MW);
      S.after(() => MW.mockReset('core'));
      await MW.mockScenario('core', 'transfer', 'BUSINESS_ERROR', { code: 'INSUFFICIENT_BALANCE', nextN: 1 });
      await S.note(['01711000001', '01711000002', 'FEE_INCOME', 'VAT_PAYABLE', 'COMMISSION_PAYABLE']);
      await S.previewP2p('customer01', '01711000002', 1000, MW.pin);
      S.status(422);
      S.code('MW-CORE-INSUFFICIENT_BALANCE');
      S.eq('english text', S.last().body && S.last().body.message, 'Insufficient balance', String(S.last()));
      S.eq('bangla text', S.last().body && S.last().body.messageBn, 'অপর্যাপ্ত ব্যালেন্স', String(S.last()));
      await S.noChange();
    },
  },
  {
    name: '[p2p frs] T3 a core timeout on the next transfer is completed by the status inquiry and debited once',
    run: async (MW, S) => {
      await prepare(MW);
      S.after(() => MW.mockReset('core'));
      await S.note(['01711000001', '01711000002', 'FEE_INCOME', 'VAT_PAYABLE', 'COMMISSION_PAYABLE']);
      // held past the core read timeout (1.5s); the next call only, so the inquiry sees the posted transfer
      await MW.mockScenario('core', 'transfer', 'TIMEOUT', { delayMs: 4000, nextN: 1 });
      await S.previewP2p('customer01', '01711000002', 1000, MW.pin);
      S.status(202);
      S.txnStatus('PROCESSING');
      const done = await MW.waitForState(S.txnId(), ['COMPLETED'], 90000);
      S.eq('the inquiry completed it', done.body && done.body.status, 'COMPLETED', String(done));
      await S.changedBy({ '01711000001': -1500, '01711000002': 1000, 'FEE_INCOME': 435, 'VAT_PAYABLE': 65, 'COMMISSION_PAYABLE': 0 });
    },
  },
  {
    name: '[p2p frs] T4 a lost core refusal is failed by the status inquiry and moves nothing',
    run: async (MW, S) => {
      await prepare(MW);
      S.after(() => MW.mockReset('core'));
      await S.note(['01711000001', '01711000002', 'FEE_INCOME', 'VAT_PAYABLE', 'COMMISSION_PAYABLE']);
      // the refusal and the lost answer are two dimensions of the same next call (the old REJECT + DROP_REPLY)
      await MW.mockScenario('core', 'transfer', 'BUSINESS_ERROR', { code: 'INSUFFICIENT_BALANCE', nextN: 1 });
      await MW.mockScenario('core', 'transfer', 'NO_CALLBACK', { nextN: 1 });
      await S.previewP2p('customer01', '01711000002', 1000, MW.pin);
      S.status(202);
      S.txnStatus('PROCESSING');
      const done = await MW.waitForState(S.txnId(), ['FAILED'], 90000);
      S.eq('the inquiry failed it', done.body && done.body.status, 'FAILED', String(done));
      S.eq('nothing was applied', done.body.error && done.body.error.code, 'MW-TMO-003', String(done));
      await S.noChange();
    },
  },
  {
    name: '[p2p frs] T6 the same Idempotency-Key twice debits once and returns the same txnId',
    run: async (MW, S) => {
      await prepare(MW);
      await S.note(['01711000001', '01711000002', 'FEE_INCOME', 'VAT_PAYABLE', 'COMMISSION_PAYABLE']);
      await S.previewP2p('customer01', '01711000002', 1000, MW.pin);
      const first = S.last().body;
      await S.repeat();
      S.status(200);
      S.sameTxnId();
      S.txnStatus('COMPLETED');
      S.eq('the repeat returns the same status', S.last().body && S.last().body.status, first && first.status, String(S.last()));
      await S.changedBy({ '01711000001': -1500, '01711000002': 1000, 'FEE_INCOME': 435, 'VAT_PAYABLE': 65, 'COMMISSION_PAYABLE': 0 });
    },
  },
  {
    name: '[p2p frs] T7 a duplicate core result event posts the transfer once',
    run: async (MW, S) => {
      await prepare(MW);
      S.after(() => MW.mockReset('core'));
      await S.note(['01711000001', '01711000002', 'FEE_INCOME', 'VAT_PAYABLE', 'COMMISSION_PAYABLE']);
      await MW.mockScenario('core', 'transfer', 'DUPLICATE_CALLBACK', { nextN: 1 });
      await S.previewP2p('customer01', '01711000002', 1000, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.changedBy({ '01711000001': -1500, '01711000002': 1000, 'FEE_INCOME': 435, 'VAT_PAYABLE': 65, 'COMMISSION_PAYABLE': 0 });
    },
  },
  {
    name: '[p2p frs] T8 a core that refuses connections fails the preview at once and moves nothing',
    run: async (MW, S) => {
      await prepare(MW);
      S.after(() => MW.mockReset('core'));
      await S.note(['01711000001', '01711000002', 'FEE_INCOME', 'VAT_PAYABLE', 'COMMISSION_PAYABLE']);
      const since = await journalSince(MW, 'core');
      await MW.mockSwitch('core', 'DOWN');
      const started = Date.now();
      S.record(await MW.app.preview('customer01', 'send-money', {
        creditParty: { id: '01711000002' }, amount: { valueMinor: 1000, currency: 'BDT' },
      }));
      S.status(503);
      S.code('MW-SYS-013');
      S.check('the preview failed without waiting out a saga deadline', Date.now() - started < 5000, Date.now() - started);
      const transfers = (await MW.mockJournal('core', { path: 'transfer' })).filter(r => (r.seq || 0) > since);
      S.eq('no transfer was sent', transfers.length, 0, transfers);
      await S.noChange();
    },
  },
  {
    name: '[p2p frs] T9 a limit is refused before any transfer and an insufficient balance moves nothing',
    run: async (MW, S) => {
      await prepare(MW);
      await S.note(['01711000001', '01711000002', 'FEE_INCOME', 'VAT_PAYABLE', 'COMMISSION_PAYABLE']);
      const since = await journalSince(MW, 'core');
      const over = S.record(await MW.app.preview('customer01', 'send-money', {
        creditParty: { id: '01711000002' }, amount: { valueMinor: 600000, currency: 'BDT' },
      }));
      S.status(422, over);
      S.code('MW-CORE-LIMIT_EXCEEDED_PER_TXN', over);
      S.eq('english limit text', over.body && over.body.message, 'The amount is above the limit for one transaction', String(over));
      S.eq('bangla limit text', over.body && over.body.messageBn, 'লেনদেনের পরিমাণ একক লেনদেনের সীমা ছাড়িয়ে গেছে', String(over));
      const transfers = (await MW.mockJournal('core', { path: 'transfer' })).filter(r => (r.seq || 0) > since);
      S.eq('the limit stopped it before a transfer', transfers.length, 0, transfers);
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
      await S.note(['01711000001', '01711000002', 'FEE_INCOME', 'VAT_PAYABLE', 'COMMISSION_PAYABLE']);
      await S.previewP2p('customer01', '01711000002', 1000, MW.pin);
      S.status(422);
      S.code('MW-CORE-INSUFFICIENT_BALANCE');
      S.eq('english balance text', S.last().body && S.last().body.message, 'Insufficient balance', String(S.last()));
      S.eq('bangla balance text', S.last().body && S.last().body.messageBn, 'অপর্যাপ্ত ব্যালেন্স', String(S.last()));
      await S.noChange();
    },
  },
  {
    // The kill itself is scripts/chaos.sh CHAOS_TARGETS=mw-orchestrator: it SIGKILLs mw-orchestrator
    // mid-flow and checks the core ledger (no money created, no double post). This request checks
    // that identity on a completed P2P, which is what the chaos run asserts per transaction.
    name: '[p2p frs] T10 a P2P keeps the ledger identity; mid-saga restart is scripts/chaos.sh',
    run: async (MW, S) => {
      await prepare(MW);
      await S.note(['01711000001', '01711000002', 'FEE_INCOME', 'VAT_PAYABLE', 'COMMISSION_PAYABLE']);
      await S.previewP2p('customer01', '01711000002', 1000, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.changedBy({ '01711000001': -1500, '01711000002': 1000, 'FEE_INCOME': 435, 'VAT_PAYABLE': 65, 'COMMISSION_PAYABLE': 0 });
      const d = await S.deltas();
      S.eq('debits equal credits', -d['01711000001'], d['01711000002'] + d['FEE_INCOME'] + d['VAT_PAYABLE'] + d['COMMISSION_PAYABLE']);
    },
  },
];

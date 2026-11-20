// Ticket 117e (FRS UC-14, UC-15, EXT-12, FL-08, FL-09, MCK-03). The simulated bank gateway (mw-simulators,
// ticket 117d) serves the bank-<code> profiles of deploy/partner-routes/bank/bank-profiles.csv: dbbl and ebl
// over REST, sonali over SOAP. Seeded account 1234567890 (Rahim Uddin) exists at every bank; an unlisted
// number opens on first use; ending 00 = not found, 95 = credit answered PENDING then confirmed by callback.
// Amount triggers: .77 = HTTP 500 after the transfer was applied, .99 = ambiguous 9876 not applied.
// W2B and add money are free (tariff v1); the bank side of the books is SETTLEMENT.
// A wallet links at most 5 accounts and a repeat link is 409, so linked() reuses the run customer's link.
// Helpers are copied into each script; a module-level binding is not.
export const helpers = [
  function books() {
    return ['01711000001', 'SETTLEMENT', 'FEE_INCOME'];
  },
  function moved(amount) {
    return { '01711000001': -amount, 'SETTLEMENT': amount, 'FEE_INCOME': 0 };
  },
  async function accounts(MW) {
    const r = await MW.http('GET', '/app/v1/bank-accounts', { headers: await MW.app.headers('customer01') });
    return r;
  },
  /** The id of the run customer's link to (bank, account), linking it (AVL first) when it is missing. */
  async function linked(MW, S, bank, account) {
    const list = await accounts(MW);
    S.status(200, list);
    const have = (list.body.accounts || []).find(a => a.bankCode === bank && a.accountLast4 === account.slice(-4));
    if (have) return have.id;
    const r = await MW.http('POST', '/app/v1/bank-accounts', {
      headers: await MW.app.headers('customer01'), json: { bankCode: bank, accountNumber: account },
    });
    S.status(201, r);
    return r.body.id;
  },
  async function w2b(MW, S, linkedAccountId, amount, pin, idem) {
    return S.submit('customer01', {
      txnType: 'W2B', linkedAccountId, amount: { valueMinor: amount, currency: 'BDT' }, encryptedPin: await MW.encryptPin(pin),
    }, idem);
  },
  async function journalSince(MW, party) {
    const rows = await MW.mockJournal(party);
    return rows.reduce((m, r) => Math.max(m, r.seq || 0), 0);
  },
  async function transfersSince(MW, party, at) {
    return (await MW.mockJournal(party, { path: 'transfers' })).filter(r => (r.seq || 0) > at && r.method === 'POST' && !/reverse/.test(r.path || ''));
  },
  async function soapTransfersSince(MW, party, at) {
    return (await MW.mockJournal(party, { path: 'v1/soap', contains: 'Transfer' })).filter(r => (r.seq || 0) > at && !/TransferStatus|ReverseTransfer/.test(r.body || ''));
  },
  async function bankBalance(MW, bank, account) {
    const r = await MW.http('GET', '/test/sim/bank/' + bank + '/accounts/' + account, { apikey: true });
    return Math.round(Number(r.body && r.body.balance) * 100);
  },
  /** The simulated bank sends its callbacks through APISIX to mw-adapter-bank (route partner-bank-callback). */
  async function callbacksTo(MW, party) {
    await MW.mock.settings(party, { callbackUrl: 'http://apisix:9080/partner/' + party + '/callback/transfers' });
  },
  async function driveInquiry(MW, S) {
    const r = await MW.http('POST', '/test/orchestrator/v1/inquiries', { apikey: true, json: { txnId: S.txnId() } });
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
    name: '[w2b avl] linking a REST bank account runs AVL and shows only the last 4 digits and the holder initial',
    run: async (MW, S) => {
      await MW.mockReset('bank-ebl');
      const before = await accounts(MW);
      for (const a of (before.body.accounts || []).filter(x => x.bankCode === 'ebl')) {
        await MW.http('DELETE', '/app/v1/bank-accounts/' + a.id, { headers: await MW.app.headers('customer01') });
      }
      const at = await journalSince(MW, 'bank-ebl');
      const r = S.record(await MW.http('POST', '/app/v1/bank-accounts', {
        headers: await MW.app.headers('customer01'), json: { bankCode: 'ebl', accountNumber: '1234567890' },
      }));
      S.status(201);
      S.eq('last 4 digits and the holder initial', [r.body.bankCode, r.body.accountLast4, r.body.holderName], ['ebl', '7890', 'R.']);
      S.check('the answer never carries the full number', !JSON.stringify(r.body).includes('1234567890'), r.body);
      const avl = (await MW.mockJournal('bank-ebl', { path: 'accounts/verify' })).filter(x => (x.seq || 0) > at);
      S.eq('the bank was asked once (AVL)', avl.length, 1, avl);
      const list = await accounts(MW);
      S.check('the list shows it masked', (list.body.accounts || []).some(a => a.id === r.body.id && a.accountLast4 === '7890')
        && !JSON.stringify(list.body).includes('1234567890'), list.body);
      const del = await MW.http('DELETE', '/app/v1/bank-accounts/' + r.body.id, { headers: await MW.app.headers('customer01') });
      S.status(204, del);
      const after = await accounts(MW);
      S.check('the unlinked account is gone', !(after.body.accounts || []).some(a => a.id === r.body.id), after.body);
    },
  },
  {
    name: '[w2b avl] an account the bank does not know is refused by AVL and nothing is linked',
    run: async (MW, S) => {
      await MW.mockReset('bank-dbbl');
      const r = S.record(await MW.http('POST', '/app/v1/bank-accounts', {
        headers: await MW.app.headers('customer01'), json: { bankCode: 'dbbl', accountNumber: '1234567800' },
      }));
      S.status(502);
      S.code('MW-PTR-BANK_DBBL-ACCOUNT_NOT_FOUND');
      const list = await accounts(MW);
      S.check('no account ending 7800 is linked', !(list.body.accounts || []).some(a => a.bankCode === 'dbbl' && a.accountLast4 === '7800'), list.body);
    },
  },
  {
    name: '[w2b avl] an account held by someone else is refused and nothing is linked',
    run: async (MW, S) => {
      // M9 review owner binding: 1234567891 is Karima Begum's, customer01 is Rahim Uddin
      await MW.mockReset('bank-ebl');
      const r = S.record(await MW.http('POST', '/app/v1/bank-accounts', {
        headers: await MW.app.headers('customer01'), json: { bankCode: 'ebl', accountNumber: '1234567891' },
      }));
      S.status(422);
      S.code('MW-VAL-ACCOUNT_HOLDER_MISMATCH');
      S.check('the answer names no holder', !JSON.stringify(r.body).includes('Karima'), r.body);
      const list = await accounts(MW);
      S.check('no account ending 7891 is linked', !(list.body.accounts || []).some(a => a.bankCode === 'ebl' && a.accountLast4 === '7891'), list.body);
    },
  },
  {
    name: '[w2b avl] linking a SOAP bank account runs AVL over SOAP and shows the holder initial',
    run: async (MW, S) => {
      await MW.mockReset('bank-sonali');
      const id = await linked(MW, S, 'sonali', '1234567890');
      const list = await accounts(MW);
      const a = (list.body.accounts || []).find(x => x.id === id);
      S.eq('sonali account masked', a && [a.bankCode, a.accountLast4, a.holderName], ['sonali', '7890', 'R.'], list.body);
    },
  },
  {
    name: '[w2b frs] T1 a transfer to a linked REST bank account previews, holds, credits the account and completes',
    run: async (MW, S) => {
      await MW.mockReset('bank-dbbl');
      const id = await linked(MW, S, 'dbbl', '1234567890');
      await S.note(books());
      const bankWas = await bankBalance(MW, 'dbbl', '1234567890');
      const at = await journalSince(MW, 'bank-dbbl');
      S.user = 'customer01';
      const body = { linkedAccountId: id, amount: { valueMinor: 50000, currency: 'BDT' } };
      const quote = S.record(await MW.app.preview('customer01', 'transfer-to-bank', body));
      S.status(200);
      S.check('the preview carries a previewId and never the full number', !!quote.body.previewId && !JSON.stringify(quote.body).includes('1234567890'), quote.body);
      // FL-09 (M9 review): the preview runs AVL again and shows the holder's initial
      S.eq('the preview shows the bank holder initial', quote.body.counterparty && quote.body.counterparty.name, 'R.', quote.body);
      S.record(await MW.app.confirm('customer01', 'transfer-to-bank', 'api-' + MW.uuid(),
        Object.assign({ previewId: quote.body.previewId, encryptedPin: await MW.encryptPin(MW.pin) }, body)));
      S.carriesTxnId();
      await S.appShows('COMPLETED', 25);
      await S.changedBy(moved(50000));
      await S.noHold('01711000001');
      S.eq('the bank account was credited 500 taka', await bankBalance(MW, 'dbbl', '1234567890') - bankWas, 50000);
      const sent = await transfersSince(MW, 'bank-dbbl', at);
      S.eq('one CREDIT transfer', sent.length, 1, sent);
    },
  },
  {
    name: '[w2b frs] T1 a transfer to a linked SOAP bank account completes and credits the account',
    run: async (MW, S) => {
      await MW.mockReset('bank-sonali');
      const id = await linked(MW, S, 'sonali', '1234567890');
      await S.note(books());
      const bankWas = await bankBalance(MW, 'sonali', '1234567890');
      const at = await journalSince(MW, 'bank-sonali');
      await w2b(MW, S, id, 41000, MW.pin);
      await S.appShows('COMPLETED', 25);
      await S.changedBy(moved(41000));
      await S.noHold('01711000001');
      S.eq('the bank account was credited 410 taka', await bankBalance(MW, 'sonali', '1234567890') - bankWas, 41000);
      const sent = await soapTransfersSince(MW, 'bank-sonali', at);
      S.eq('one SOAP Transfer', sent.length, 1, sent);
    },
  },
  {
    name: '[w2b frs] T2 a bank refusal shows the mapped bank error, releases the hold and moves nothing',
    run: async (MW, S) => {
      await MW.mockReset('bank-dbbl');
      S.after(() => MW.mockReset('bank-dbbl'));
      const id = await linked(MW, S, 'dbbl', '1234567890');
      await MW.mockScenario('bank-dbbl', 'transfer', 'BUSINESS_ERROR', { code: '4005', nextN: 1 });
      await S.note(books());
      await w2b(MW, S, id, 42000, MW.pin);
      await S.appShows('FAILED', 25, 'MW-PTR-BANK_DBBL-TRANSFER_REFUSED');
      await S.noChange();
      await S.noHold('01711000001');
    },
  },
  {
    name: '[w2b tsq frs] T3 a credit answered 500 after it was applied is completed by status inquiry and debited once',
    run: async (MW, S) => {
      await MW.mockReset('bank-dbbl');
      const id = await linked(MW, S, 'dbbl', '1234567890');
      await S.note(books());
      const at = await journalSince(MW, 'bank-dbbl');
      await w2b(MW, S, id, 43077, MW.pin);
      await driveInquiry(MW, S);
      await S.appShows('COMPLETED', 30);
      await S.changedBy(moved(43077));
      await S.noHold('01711000001');
      const sent = await transfersSince(MW, 'bank-dbbl', at);
      S.eq('the bank was asked to credit once (no second pay)', sent.length, 1, sent);
    },
  },
  {
    name: '[w2b tsq frs] T4 an ambiguous refusal the status inquiry does not find is released and moves nothing',
    run: async (MW, S) => {
      await MW.mockReset('bank-dbbl');
      const id = await linked(MW, S, 'dbbl', '1234567890');
      await S.note(books());
      await w2b(MW, S, id, 44099, MW.pin);
      await driveInquiry(MW, S);
      await S.appShows('FAILED', 30);
      await S.noChange();
      await S.noHold('01711000001');
    },
  },
  {
    name: '[w2b frs] T6 the same Idempotency-Key twice transfers to the bank once and returns the same txnId',
    run: async (MW, S) => {
      await MW.mockReset('bank-dbbl');
      const id = await linked(MW, S, 'dbbl', '1234567890');
      await S.note(books());
      const at = await journalSince(MW, 'bank-dbbl');
      await w2b(MW, S, id, 45000, MW.pin);
      await S.appShows('COMPLETED', 25);
      await S.repeat();
      S.sameTxnId();
      await S.changedBy(moved(45000));
      const sent = await transfersSince(MW, 'bank-dbbl', at);
      S.eq('one CREDIT transfer', sent.length, 1, sent);
    },
  },
  {
    name: '[w2b callback frs] T7 a pending credit the bank confirms twice by callback completes once',
    run: async (MW, S) => {
      await MW.mockReset('bank-dbbl');
      S.after(() => MW.mockReset('bank-dbbl'));
      const id = await linked(MW, S, 'dbbl', '1234567895');
      await callbacksTo(MW, 'bank-dbbl');
      await MW.mockScenario('bank-dbbl', null, 'DUPLICATE_CALLBACK', { nextN: 1 });
      await S.note(books());
      await w2b(MW, S, id, 46000, MW.pin);
      await S.appShows('COMPLETED', 30);
      await S.changedBy(moved(46000));
      await S.noHold('01711000001');
      await S.appShows('COMPLETED');
    },
  },
  {
    name: '[w2b frs] T8 a bank that is DOWN fails the transfer fast and moves nothing',
    run: async (MW, S) => {
      await MW.mockReset('bank-dbbl');
      S.after(async () => {
        await MW.mockReset('bank-dbbl');
        await MW.mockSwitch('bank-dbbl', 'UP');
        await untilHealth(MW, S, 'bank-dbbl', 'UP');
      });
      const id = await linked(MW, S, 'dbbl', '1234567890');
      const at = await journalSince(MW, 'bank-dbbl');
      await MW.mockSwitch('bank-dbbl', 'DOWN');
      await untilHealth(MW, S, 'bank-dbbl', 'DOWN');
      await S.note(books());
      const t0 = Date.now();
      await w2b(MW, S, id, 47000, MW.pin);
      S.status(503);
      S.check('refused fast (under 5 s)', Date.now() - t0 < 5000, Date.now() - t0);
      await S.noChange();
      await S.noHold('01711000001');
      const sent = await transfersSince(MW, 'bank-dbbl', at);
      S.eq('no transfer reached the bank', sent.length, 0, sent);
    },
  },
  {
    name: '[w2b frs] T9 a limit is refused before any bank call and an insufficient balance moves nothing',
    run: async (MW, S) => {
      await MW.mockReset('bank-dbbl');
      const id = await linked(MW, S, 'dbbl', '1234567890');
      const at = await journalSince(MW, 'bank-dbbl');
      await S.note(books());
      const over = S.record(await MW.app.preview('customer01', 'transfer-to-bank', {
        linkedAccountId: id, amount: { valueMinor: 999999999, currency: 'BDT' },
      }));
      S.check('the preview refuses the limit', over.status === 422 && /^MW-CORE-LIMIT_EXCEEDED/.test(over.body && over.body.code), String(over));

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
      await w2b(MW, S, id, 48000, MW.pin);
      S.status(422);
      S.code('MW-CORE-INSUFFICIENT_BALANCE');
      await S.noChange();
      const sent = await transfersSince(MW, 'bank-dbbl', at);
      S.eq('no transfer reached the bank', sent.length, 0, sent);
    },
  },
  {
    name: '[w2b frs] T10 a transfer to bank keeps the ledger identity; mid-saga restart is scripts/chaos.sh',
    run: async (MW, S) => {
      await MW.mockReset('bank-dbbl');
      const id = await linked(MW, S, 'dbbl', '1234567890');
      await S.note(['01711000001', 'SETTLEMENT', 'FEE_INCOME', 'VAT_PAYABLE', 'COMMISSION_PAYABLE']);
      await w2b(MW, S, id, 49000, MW.pin);
      await S.appShows('COMPLETED', 25);
      await S.changedBy({ '01711000001': -49000, 'SETTLEMENT': 49000, 'FEE_INCOME': 0, 'VAT_PAYABLE': 0, 'COMMISSION_PAYABLE': 0 });
      const d = await S.deltas();
      S.eq('debits equal credits', -d['01711000001'], d.SETTLEMENT + d.FEE_INCOME + d.VAT_PAYABLE + d.COMMISSION_PAYABLE);
    },
  },
  {
    name: '[addmoney c2w callback] add money from a linked bank account is debited at the bank and credited once by callback',
    run: async (MW, S) => {
      await MW.mockReset('bank-dbbl');
      S.after(() => MW.mockReset('bank-dbbl'));
      const id = await linked(MW, S, 'dbbl', '1234567890');
      await callbacksTo(MW, 'bank-dbbl');
      await S.note(books());
      const bankWas = await bankBalance(MW, 'dbbl', '1234567890');
      await S.submit('customer01', {
        txnType: 'ADD_MONEY_BANK', linkedAccountId: id, amount: { valueMinor: 30000, currency: 'BDT' }, encryptedPin: await MW.encryptPin(MW.pin),
      });
      await S.appShows('COMPLETED', 30);
      await S.changedBy({ '01711000001': 30000, 'SETTLEMENT': -30000, 'FEE_INCOME': 0 });
      await S.noHold('01711000001');
      S.eq('the bank account was debited 300 taka', bankWas - await bankBalance(MW, 'dbbl', '1234567890'), 30000);
      S.check('the status answer masks the bank account', !JSON.stringify(S.last().body).includes('1234567890'), S.last().body);
    },
  },
];

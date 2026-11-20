// ADR D25: GET /app/v1/transactions. The history is read from mw-orchestrator's query API, so a
// transaction shows there once the orchestrator committed it: positive checks poll. customer01 is
// the run customer (masked 0171****001 like customer01), customer02 01711000002, agent01
// 01811000001, merchant01 01911000001.
export default [
  {
    name: '[history p2p] a sent transfer is OUT in the payer\'s history and IN in the payee\'s',
    run: async (MW, S) => {
      await S.txn('customer01', 'P2P', '01711000002', 1000, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      const id = S.txnId();
      const mine = await findIn(MW, 'customer01', id);
      S.check('customer01 sees the transfer in its history', !!mine.item, String(mine.r));
      S.eq('OUT, P2P, COMPLETED, 1000, payee masked as 0171****002',
        [mine.item.direction, mine.item.txnType, mine.item.status, mine.item.amount.valueMinor, mine.item.counterparty.id],
        ['OUT', 'P2P', 'COMPLETED', 1000, '0171****002']);
      const theirs = await findIn(MW, 'customer02', id);
      S.check('customer02 sees the transfer in its history', !!theirs.item, String(theirs.r));
      S.eq('IN, payer masked as 0171****001, no fee, no error',
        [theirs.item.direction, theirs.item.counterparty.id, 'fee' in theirs.item, 'error' in theirs.item],
        ['IN', '0171****001', false, false]);
      S.check('no full MSISDN in either history', !/01711000001|01711000002/.test(JSON.stringify([mine.r.body, theirs.r.body])), 'unmasked number');
    },
  },
  {
    name: '[history cash] an agent cash-in is IN in the customer\'s history and OUT in the agent\'s',
    run: async (MW, S) => {
      await S.txn('agent01', 'CASH_IN', '01711000001', 1500, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      const id = S.txnId();
      const customer = await findIn(MW, 'customer01', id, 'direction=IN&limit=50');
      S.check('customer01 sees the cash-in as IN', !!customer.item, String(customer.r));
      S.eq('CASH_IN, IN, agent masked as 0181****001', [customer.item.txnType, customer.item.direction, customer.item.counterparty.id],
        ['CASH_IN', 'IN', '0181****001']);
      const agent = await findIn(MW, 'agent01', id);
      S.check('agent01 sees the cash-in as OUT', !!agent.item && agent.item.direction === 'OUT', String(agent.r));
    },
  },
  {
    name: '[history merchant] a merchant payment is IN in the merchant\'s history',
    run: async (MW, S) => {
      await S.txn('customer01', 'MERCHANT_PAY', '01911000001', 1200, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      const id = S.txnId();
      const merchant = await findIn(MW, 'merchant01', id);
      S.check('merchant01 sees the payment in its history', !!merchant.item, String(merchant.r));
      S.eq('MERCHANT_PAY, IN, payer masked as 0171****001', [merchant.item.txnType, merchant.item.direction, merchant.item.counterparty.id],
        ['MERCHANT_PAY', 'IN', '0171****001']);
      const other = await MW.app.history('merchant02', 'limit=100');
      S.status(200, other);
      S.check('merchant02 does not see it', !((other.body || {}).items || []).some(i => i.txnId === id), String(other));
    },
  },
  {
    name: '[history] the history filters by direction, pages with hasMore and refuses bad parameters',
    run: async (MW, S) => {
      const out = await MW.app.history('customer01', 'direction=OUT&limit=20');
      S.status(200, out);
      S.check('only OUT items', (out.body.items || []).every(i => i.direction === 'OUT'), String(out));
      const one = await MW.app.history('customer01', 'limit=1');
      S.status(200, one);
      S.eq('one item, limit 1, offset 0, hasMore a boolean', [one.body.items.length <= 1, one.body.limit, one.body.offset, typeof one.body.hasMore],
        [true, 1, 0, 'boolean']);
      S.record(await MW.app.history('customer01', 'limit=0'));
      S.status(400);
      S.code('MW-VAL-001');
      S.record(await MW.app.history('customer01', 'direction=SIDEWAYS'));
      S.status(400);
      S.code('MW-VAL-001');
    },
  },
  {
    name: '[history] the signed-in wallet saves a biller account and another wallet cannot see it',
    run: async (MW, S) => {
      const account = ('9' + MW.unique()).replace(/[^0-9A-Za-z]/g, '').slice(0, 20);
      const saved = await MW.http('POST', '/app/v1/saved-billers', {
        headers: await MW.app.headers('customer01'),
        json: { billerCode: 'DESCO', accountNo: account, nickname: 'home' },
      });
      S.record(saved);
      S.status(201);
      S.eq('the owner sees the account number', saved.body.accountNo, account);
      const id = saved.body.id;
      const mine = await MW.http('GET', '/app/v1/saved-billers', { headers: await MW.app.headers('customer01') });
      S.record(mine);
      S.status(200);
      S.check('the owner lists that biller', (mine.body.billers || []).some(b => b.id === id && b.accountNo === account), String(mine));
      const other = await MW.http('GET', '/app/v1/saved-billers', { headers: await MW.app.headers('customer02') });
      S.record(other);
      S.status(200);
      S.check('another wallet does not see it', !(other.body.billers || []).some(b => b.id === id || b.accountNo === account), String(other));
      S.record(await MW.http('DELETE', '/app/v1/saved-billers/' + id, { headers: await MW.app.headers('customer02') }));
      S.status(404);
      S.code('MW-VAL-003');
      S.record(await MW.http('DELETE', '/app/v1/saved-billers/' + id, { headers: await MW.app.headers('customer01') }));
      S.status(204);
    },
  },
  {
    name: '[history] the signed-in wallet saves a favourite and another wallet cannot delete it',
    run: async (MW, S) => {
      const msisdn = '01719' + String(Date.now()).slice(-6);
      const saved = await MW.http('POST', '/app/v1/favourites', {
        headers: await MW.app.headers('customer01'),
        json: { msisdn: msisdn, label: 'Amma' },
      });
      S.record(saved);
      S.status(201);
      S.eq('the owner sees the number', saved.body.msisdn, msisdn);
      const id = saved.body.id;
      const mine = await MW.http('GET', '/app/v1/favourites', { headers: await MW.app.headers('customer01') });
      S.record(mine);
      S.status(200);
      S.check('the owner lists that favourite', (mine.body.favourites || []).some(f => f.id === id && f.msisdn === msisdn), String(mine));
      S.record(await MW.http('DELETE', '/app/v1/favourites/' + id, { headers: await MW.app.headers('customer02') }));
      S.status(404);
      S.code('MW-VAL-003');
      S.record(await MW.http('DELETE', '/app/v1/favourites/' + id, { headers: await MW.app.headers('customer01') }));
      S.status(204);
    },
  },
  {
    name: '[history] a statement longer than 90 days is refused and a short range lists the transfer',
    run: async (MW, S) => {
      await S.txn('customer01', 'P2P', '01711000002', 1000, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      const id = S.txnId();
      const longFrom = '2026-01-01T00:00:00Z';
      const longTo = '2026-05-01T00:00:00Z';
      S.record(await MW.http('GET', '/app/v1/statement?from=' + encodeURIComponent(longFrom) + '&to=' + encodeURIComponent(longTo), {
        headers: await MW.app.headers('customer01'),
      }));
      S.status(400);
      S.code('MW-VAL-001');
      const from = new Date(Date.now() - 2 * 86400000).toISOString();
      const to = new Date(Date.now() + 86400000).toISOString();
      const query = 'from=' + encodeURIComponent(from) + '&to=' + encodeURIComponent(to);
      const st = await MW.http('GET', '/app/v1/statement?' + query, { headers: await MW.app.headers('customer01') });
      S.record(st);
      S.status(200);
      S.check('the transfer is a debit on the statement', (st.body.entries || []).some(e => e.txnId === id && e.direction === 'DEBIT' && e.amount.valueMinor === 1000), String(st));
      S.check('the statement does not carry the raw MSISDN', !JSON.stringify(st.body).includes('01711000001'), String(st));
      const csv = await MW.http('GET', '/app/v1/statement?' + query, {
        headers: Object.assign({ Accept: 'text/csv' }, await MW.app.headers('customer01')),
      });
      S.record(csv);
      S.status(200);
      S.check('the csv lists the transfer and not the raw number', csv.raw.includes(id) && !csv.raw.includes('01711000001'), csv.raw);
    },
  },
];

// Shared step of the folder, inlined into each script by the builder (build_collection.mjs, `helpers`):
// polls the user's history until the transaction shows.
async function findIn(MW, user, txnId, query) {
  const item = r => r.status === 200 ? ((r.body || {}).items || []).find(i => i.txnId === txnId) : undefined;
  const r = await MW.poll(() => MW.app.history(user, query || 'limit=50'), x => !!item(x), 20000, 700);
  return { r, item: item(r) };
}
export const helpers = [findIn];

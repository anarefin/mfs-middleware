// Ticket 130 (FRS AUD-01, AUD-03..05): mw-audit-dw's search projection (mw.txn.status.v1 ->
// audit.txn_index), the per-hop timeline and the audited CSV export, through mw-audit-dw's test-only
// API (/test/audit-dw/v1/audit/*, same services as the internal API without the services-realm token).
export const helpers = [
  async function auditGet(MW, path) {
    return MW.http('GET', '/test/audit-dw/v1/audit/' + path, { apikey: true });
  },
];

const MSISDN = /01[3-9]\d{8}/;

export default [
  {
    name: '[audit topup] a search by partner and amount range finds the top-up and its partner call',
    run: async (MW, S) => {
      await S.topup('customer01', 'gp', 'PREPAID', '01711000002', 2000, MW.pin);
      S.status(200);
      const id = S.txnId();
      const q = 'partner=gp&amountMin=2000&amountMax=2000&limit=500&from='
        + encodeURIComponent(new Date(Date.now() - 10 * 60000).toISOString());
      const r = await MW.poll(() => auditGet(MW, 'events?' + q),
        x => ((x.body || {}).items || []).some(e => e.txnId === id && e.action === 'partner.call.completed'), 60000, 1000);
      S.status(200, r);
      const mine = r.body.items.filter(e => e.txnId === id);
      S.check('the top-up is found by partner gp and its amount (2000 poisha)', mine.length > 0, r.body);
      S.check('its partner call is the PARTNER_CALL hop', mine.some(e => e.hop === 'PARTNER_CALL' && e.direction === 'OUT'), mine);
      const other = await auditGet(MW, 'events?txnId=' + id + '&amountMin=2001');
      S.eq('another amount range does not find it', ((other.body || {}).items || []).length, 0, String(other));
    },
  },
  {
    name: '[audit p2p] the timeline of a P2P lists its hops in order with latencies and no clear MSISDN',
    run: async (MW, S) => {
      await S.p2p('customer01', '01711000002', 1000, MW.pin);
      S.status(200);
      const id = S.txnId();
      const r = await MW.poll(() => auditGet(MW, 'transactions/' + id + '/timeline'),
        x => ((x.body || {}).hops || []).some(h => (h.resource || '') === 'txn/' + id && /COMPLETED/.test(JSON.stringify(h))), 60000, 1000);
      S.status(200, r);
      const hops = r.body.hops || [];
      S.check('every orchestrator step is a hop', hops.filter(h => h.hop === 'ORCHESTRATOR').length >= 2, hops);
      const since = hops.map(h => h.sinceStartMs);
      S.check('hops are in the order they happened', since.every((v, i) => i === 0 || v >= since[i - 1]), since);
      S.eq('the first hop starts the timeline', since[0], 0, since);
      S.check('later hops carry the gap to the previous one', hops.slice(1).every(h => typeof h.gapMs === 'number'), hops);
      S.check('no clear MSISDN in the timeline', !MSISDN.test(r.raw), r.raw);
    },
  },
  {
    name: '[audit] a CSV export of a transaction returns its rows and records one operator action',
    run: async (MW, S) => {
      await S.p2p('customer01', '01711000002', 1000, MW.pin);
      S.status(200);
      const id = S.txnId();
      await MW.poll(() => auditGet(MW, 'events?txnId=' + id), x => ((x.body || {}).items || []).length >= 2, 60000, 1000);
      const operator = 'ops-e2e-auditor-' + id.slice(0, 8);
      const csv = await MW.http('GET', '/test/audit-dw/v1/audit/search/export.csv?txnId=' + id,
        { apikey: true, headers: { 'X-Mw-Operator-Id': operator } });
      S.status(200, csv);
      const lines = csv.raw.split('\r\n').filter(l => l);
      S.check('a header row and the transaction rows', lines[0].startsWith('at,recordedAt,auditId,txnId') && lines.length >= 3, csv.raw);
      S.check('every row is the transaction', lines.slice(1).every(l => l.includes(id)), csv.raw);
      S.eq('not truncated', csv.header('X-Mw-Export-Truncated'), 'false');
      const rec = await MW.poll(() => auditGet(MW, 'events?action=audit.search.exported&actorId=' + operator),
        x => ((x.body || {}).items || []).length >= 1, 10000, 500);
      const items = (rec.body || {}).items || [];
      S.eq('the export wrote one operator-action record', items.length, 1, items);
      S.eq('it names the rows exported', items[0] && items[0].after && items[0].after.rowCount, lines.length - 1, items);
      const refused = await MW.http('GET', '/test/audit-dw/v1/audit/search/export.csv?txnId=' + id, { apikey: true });
      S.status(400, refused);
    },
  },
];

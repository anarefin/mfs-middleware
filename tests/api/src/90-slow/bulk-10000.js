// From bulk-execution.feature: the 10,000-row payout still takes minutes with the test timers (200
// rows/s plus settlement), so it runs only with ALL=1 (tests/api/run.sh --all).
export default [
  {
    name: '[bulk] a 10,000-row batch completes, the result file matches and the org balance is correct',
    run: async (MW, S) => {
      await MW.portal.idle('org-checker01');
      const up = S.record(await MW.portal.upload('org-maker01', MW.portal.payoutFile(10000, '1.00')));
      S.status(201);
      const id = up.body.batchId;
      // not 01711000001: the run plays customer01's number as the run customer (lib.js forRun), the file pays the real one
      await S.note(['01311000001', '01711000002', '01711001000']);
      S.record(await MW.portal.approve('org-checker01', id));
      S.status(200);
      const done = await MW.poll(() => MW.portal.get('org-checker01', id), r => r.body && r.body.status === 'COMPLETED', 900000, 3000);
      S.eq('within 15 minutes the batch is COMPLETED', done.body.status, 'COMPLETED', String(done));
      const p = done.body.progress;
      const r = await MW.portal.result('org-maker01', id);
      const codes = {};
      r.raw.split('\n').slice(1).filter(l => l && l.split(',')[4] !== 'COMPLETED').forEach(l => { const c = l.split(','); codes[c[4] + ' ' + c[6]] = (codes[c[4] + ' ' + c[6]] || 0) + 1; });
      S.eq('10000 completed, 0 failed, 1000000 poisha paid', [p.completed, p.failed, p.pending, p.inFlight, p.paidAmount.valueMinor], [10000, 0, 0, 0, 1000000],
        Object.keys(codes).length ? JSON.stringify(codes) : undefined);
      S.status(200, r);
      const lines = r.raw.split('\n').filter(l => l.length);
      S.eq('result file header', lines[0], 'line,msisdn,amount,reference,status,txnId,errorCode,batchStatus');
      const rows = lines.slice(1).map(l => l.split(','));
      S.eq('10000 rows', rows.length, 10000);
      S.check('every row COMPLETED with a masked MSISDN', rows.every(l => l[4] === 'COMPLETED' && /^01\d{2}\*{4}\d{3}$/.test(l[1])), rows.slice(0, 3));
      S.eq('each with its own txnId', new Set(rows.map(l => l[5])).size, 10000);
      S.check('every row names the batch status COMPLETED', rows.every(l => l[7] === 'COMPLETED'));
      await S.changedBy({ '01311000001': -1000000, '01711000002': 1000, '01711001000': 1000 });
    },
  },
];

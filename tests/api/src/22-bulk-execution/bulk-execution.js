// Port of bulk-execution.feature (the 10,000-row payout is in 90 Slow). ORG001 pays from org wallet
// 01311000001 (no PIN, no fee); payout files pay the core mock's customers 01711000001.. in turn.
// Background: no batch of org-checker01's org is still sending rows. The pause scenario pays 1,000
// rows (Cucumber: 2,000), 20 s at the 50 rows/s dispatch rate.
export default [
  {
    name: '[bulk] a row the core refuses is reported in the result file and costs the org nothing',
    run: async (MW, S) => {
      await MW.portal.idle('org-checker01');
      const csv = 'msisdn,amount,reference\n01711000002,2.00,OK-' + MW.unique() + '\n01710000999,3.00,NOPE-' + MW.unique() + '\n';
      const refs = csv.trim().split('\n').slice(1).map(l => l.split(',')[2]);
      const up = S.record(await MW.portal.upload('org-maker01', csv));
      S.status(201);
      await S.note(['01311000001', '01711000002']);
      S.record(await MW.portal.approve('org-checker01', up.body.batchId));
      const done = await MW.poll(() => MW.portal.get('org-checker01', up.body.batchId), r => r.body && r.body.status === 'COMPLETED', 120000, 1000);
      S.eq('within 2 minutes the batch is COMPLETED', done.body.status, 'COMPLETED', String(done));
      const p = done.body.progress;
      S.eq('1 completed, 1 failed, 200 poisha paid, none pending', [p.completed, p.failed, p.pending, p.inFlight, p.paidAmount.valueMinor], [1, 1, 0, 0, 200]);
      const lines = await resultLines(MW, S, 'org-maker01', up.body.batchId);
      S.eq('the result file shows reference, status, errorCode', lines.map(l => [l[3], l[4], l[6] || '']),
        [[refs[0], 'COMPLETED', ''], [refs[1], 'REJECTED', 'MW-CORE-CREDIT_WALLET_NOT_FOUND']]);
      S.check('every line names the batch status COMPLETED', lines.every(l => l[7] === 'COMPLETED'), lines);
      await S.changedBy({ '01311000001': -200, '01711000002': 200 });
    },
  },
  {
    name: '[bulk] a paused batch sends nothing until it is resumed, then completes',
    run: async (MW, S) => {
      await MW.portal.idle('org-checker01');
      const up = S.record(await MW.portal.upload('org-maker01', MW.portal.payoutFile(1000, '1.00')));
      S.status(201);
      const id = up.body.batchId;
      await S.note(['01311000001']);
      S.record(await MW.portal.approve('org-checker01', id));
      const paused = S.record(await MW.portal.pause('org-checker01', id));
      S.status(200);
      S.eq('the batch status is PAUSED', paused.body.status, 'PAUSED');
      // a short negative window (Cucumber: 3 s): nothing more is sent while paused
      const pending = async () => (await MW.portal.get('org-checker01', id)).body.progress.pending;
      const before = await pending();
      S.check('rows still unsent when paused', before > 0, before);
      await MW.sleep(3000);
      S.eq('org-checker01 sees the batch send no more rows', await pending(), before);
      S.record(await MW.portal.pause('org-maker01', id));
      S.status(403);
      const resumed = S.record(await MW.portal.resume('org-checker01', id));
      S.status(200);
      S.eq('the batch status is EXECUTING', resumed.body.status, 'EXECUTING');
      const done = await MW.poll(() => MW.portal.get('org-checker01', id), r => r.body && r.body.status === 'COMPLETED', 300000, 2000);
      S.eq('within 5 minutes the batch is COMPLETED', done.body.status, 'COMPLETED', String(done));
      const p = done.body.progress;
      let why = '';
      if (p.failed) { // name the failed rows' error codes in the failure message
        const lines = await resultLines(MW, S, 'org-maker01', id);
        const codes = {};
        lines.filter(l => l[4] !== 'COMPLETED').forEach(l => { codes[l[6] + ' ' + l[1]] = (codes[l[6] + ' ' + l[1]] || 0) + 1; });
        why = JSON.stringify(codes);
      }
      S.eq('1000 completed, 0 failed, 100000 poisha paid', [p.completed, p.failed, p.pending, p.inFlight, p.paidAmount.valueMinor], [1000, 0, 0, 0, 100000], why || undefined);
      const q = 'resource=' + encodeURIComponent('bulk-batch/' + id);
      const want = [['bulk.batch.uploaded', 'org-maker01'], ['bulk.batch.approved', 'org-checker01'], ['bulk.batch.paused', 'org-checker01'], ['bulk.batch.resumed', 'org-checker01']];
      const a = await MW.poll(() => MW.testApi.auditEvents(q), x => ((x.body || {}).items || []).length >= want.length, 60000, 1000);
      S.eq('the audit trail of the batch shows upload, approve, pause, resume', ((a.body || {}).items || []).map(e => [e.action, e.actor.id]), want);
      await S.changedBy({ '01311000001': -100000 });
    },
  },
];

async function resultLines(MW, S, user, batchId) {
  const r = await MW.portal.result(user, batchId);
  S.status(200, r);
  const lines = r.raw.split('\n').filter(l => l.length);
  S.eq('result file header', lines[0], 'line,msisdn,amount,reference,status,txnId,errorCode,batchStatus');
  return lines.slice(1).map(l => l.split(','));
}
export const helpers = [resultLines];

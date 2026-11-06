// Port of bulk-upload.feature. Realm portals: org-maker01, org-checker01, org-makerchecker01 (both
// roles) of ORG001, org-checker02 of ORG002. The batch audit is read through mw-audit-dw's test-only
// GET /test/audit-dw/v1/audit/events?resource=bulk-batch/<id> (ticket 58; Cucumber used psql).
export default [
  {
    name: '[bulk] a valid file is stored awaiting approval and a different checker approves it',
    run: async (MW, S) => {
      const up = S.record(await MW.portal.upload('org-maker01', MW.portal.file(3)));
      S.status(201);
      S.eq('PENDING_APPROVAL, 3 rows totalling 300 poisha', [up.body.status, up.body.rowCount, up.body.totalAmount.valueMinor], ['PENDING_APPROVAL', 3, 300]);
      const ok = S.record(await MW.portal.approve('org-checker01', up.body.batchId));
      S.status(200);
      S.eq('APPROVED, decided by org-checker01', [ok.body.status, ok.body.checkerId], ['APPROVED', 'org-checker01']);
      await batchAudit(MW, S, up.body.batchId, [['bulk.batch.uploaded', 'org-maker01'], ['bulk.batch.approved', 'org-checker01']]);
    },
  },
  {
    name: '[bulk] a checker rejects a batch',
    run: async (MW, S) => {
      const up = S.record(await MW.portal.upload('org-maker01', MW.portal.file(2)));
      S.status(201);
      const rej = S.record(await MW.portal.reject('org-checker01', up.body.batchId, 'wrong payroll month'));
      S.status(200);
      S.eq('the batch status is REJECTED', rej.body.status, 'REJECTED');
      S.record(await MW.portal.approve('org-checker01', up.body.batchId));
      S.status(409);
      S.code('MW-VAL-032');
      await batchAudit(MW, S, up.body.batchId, [['bulk.batch.uploaded', 'org-maker01'], ['bulk.batch.rejected', 'org-checker01']]);
    },
  },
  {
    name: '[bulk] the maker cannot approve their own batch, even with the checker role',
    run: async (MW, S) => {
      const up = S.record(await MW.portal.upload('org-makerchecker01', MW.portal.file(2)));
      S.status(201);
      S.record(await MW.portal.approve('org-makerchecker01', up.body.batchId));
      S.status(403);
      S.code('MW-AUTH-030');
      const look = S.record(await MW.portal.get('org-checker01', up.body.batchId));
      S.eq('the batch status is PENDING_APPROVAL', look.body.status, 'PENDING_APPROVAL');
    },
  },
  {
    name: '[bulk] another org cannot see or approve the batch',
    run: async (MW, S) => {
      const up = S.record(await MW.portal.upload('org-maker01', MW.portal.file(1)));
      S.status(201);
      S.record(await MW.portal.approve('org-checker02', up.body.batchId));
      S.status(404);
      S.code('MW-VAL-003');
    },
  },
  {
    name: '[bulk] the org lists its batches by any batch status, the M3 ones included',
    run: async (MW, S) => {
      for (const st of ['EXPIRED', 'COMPLETED_WITH_EXCEPTIONS']) {
        const r = S.record(await MW.portal.list('org-checker01', st));
        S.status(200);
        S.check('every listed batch has status ' + st, ((r.body || {}).batches || []).every(b => b.status === st), String(r));
      }
      S.record(await MW.portal.list('org-checker01', 'NOPE'));
      S.status(400);
      S.code('MW-VAL-001');
    },
  },
  {
    name: '[bulk] 10,000 rows are accepted',
    run: async (MW, S) => {
      const up = S.record(await MW.portal.upload('org-maker01', MW.portal.file(10000)));
      S.status(201);
      S.eq('10000 rows totalling 1000000 poisha', [up.body.rowCount, up.body.totalAmount.valueMinor], [10000, 1000000]);
    },
  },
  {
    name: '[bulk] 10,001 rows are rejected',
    run: async (MW, S) => {
      S.record(await MW.portal.upload('org-maker01', MW.portal.file(10001)));
      S.status(400);
      S.code('MW-VAL-030');
    },
  },
  {
    name: '[bulk] malformed rows are reported per line and nothing is stored',
    run: async (MW, S) => {
      const csv = ['msisdn,amount,reference', '01711000001,1.00,E2E-A', '0171,1.00,E2E-B', '01711000003,0,E2E-C', '01711000004,1.00,E2E-A'].join('\n') + '\n';
      const r = S.record(await MW.portal.upload('org-maker01', csv));
      S.status(400);
      S.code('MW-VAL-031');
      S.eq('the error details name these fields', (r.body.details || []).map(d => d.field), ['line 3: msisdn', 'line 4: amount', 'line 5: reference']);
    },
  },
];

async function batchAudit(MW, S, batchId, expected) {
  const q = 'resource=' + encodeURIComponent('bulk-batch/' + batchId);
  const r = await MW.poll(() => MW.testApi.auditEvents(q), x => ((x.body || {}).items || []).length >= expected.length, 60000, 1000);
  S.eq('within 60 seconds the audit trail of the batch shows ' + JSON.stringify(expected),
    ((r.body || {}).items || []).map(e => [e.action, e.actor.id]), expected);
}
export const helpers = [batchAudit];

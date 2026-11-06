// Port of operator-console.feature (acceptance criterion 4): search by txnId and by MSISDN, the
// saga timeline and the audit trail of the admin view, a reversal approved by a second operator.
export default [
  {
    name: '[admin operator p2p] an operator finds a P2P by txnId and by MSISDN and sees its timeline and audit trail',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE-POOL']);
      await S.p2p('customer01', '01711000002', 1000, '1234');
      S.status(200);
      const id = S.txnId();
      const mine = r => ((r.body || {}).items || []).filter(t => t.txnId === id);
      const byId = await MW.admin.search('ops-e2e-checker01', 'txnId', id);
      S.eq('the search by txnId lists only the transaction', ((byId.body || {}).items || []).map(t => t.txnId), [id], String(byId));
      S.eq('found COMPLETED, initiator masked 0171****001', mine(byId).map(t => [t.status, t.initiator.id]), [['COMPLETED', '0171****001']]);
      const byMsisdn = await MW.poll(() => MW.admin.search('ops-e2e-checker01', 'msisdn', '+8801711000001', 'P2P'), r => r.status === 200 && mine(r).length > 0, 10000, 700);
      S.eq('the search by MSISDN +8801711000001 finds it COMPLETED, masked', mine(byMsisdn).map(t => [t.status, t.initiator.id]), [['COMPLETED', '0171****001']], String(byMsisdn));
      const d = await MW.admin.txn('ops-e2e-checker01', id);
      S.status(200, d);
      const states = d.body.timeline.filter(e => e.kind === 'STATE').map(e => e.to);
      const want = ['VALIDATING', 'POSTING', 'COMPLETED'];
      let i = 0;
      states.forEach(s => { if (s === want[i]) i++; });
      S.check('the admin timeline shows the states VALIDATING, POSTING, COMPLETED (in order, last COMPLETED)', i === want.length && states[states.length - 1] === 'COMPLETED', states);
      S.check('the admin timeline shows the core command PostTransfer', d.body.timeline.some(e => e.kind === 'CORE' && e.command === 'PostTransfer'), d.body.timeline);
      await S.auditStates('RECEIVED, VALIDATING, POSTING, COMPLETED');
    },
  },
  {
    name: '[admin operator reversal] an operator finds a P2P by MSISDN and reverses it once a second operator approves',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE-POOL']);
      await S.p2p('customer01', '01711000002', 1000, '1234');
      S.status(200);
      const id = S.txnId();
      const mine = r => ((r.body || {}).items || []).filter(t => t.txnId === id);
      const byMsisdn = await MW.poll(() => MW.admin.search('ops-e2e-maker01', 'msisdn', '01711000001', 'P2P'), r => r.status === 200 && mine(r).length > 0, 10000, 700);
      S.eq('the search by MSISDN 01711000001 finds it COMPLETED, masked', mine(byMsisdn).map(t => [t.status, t.initiator.id]), [['COMPLETED', '0171****001']], String(byMsisdn));
      S.crFrom(await MW.admin.ask('ops-e2e-maker01', id, 'REVERSE', 'customer reported a wrong number'));
      await S.crIs('PENDING');
      await S.adminShows('COMPLETED', 5);
      await MW.admin.approve('ops-e2e-checker01', S.crId);
      await S.crIs('APPROVED');
      await S.adminShows('REVERSED', 30);
      await S.noChange();
      const byId = await MW.admin.search('ops-e2e-checker01', 'txnId', id);
      S.eq('the search by txnId finds it REVERSED, masked', mine(byId).map(t => [t.status, t.initiator.id]), [['REVERSED', '0171****001']], String(byId));
      await S.auditDecision('APPROVED');
      await S.auditStates('RECEIVED, VALIDATING, POSTING, COMPLETED, REVERSING, REVERSED');
    },
  },
];

// Ticket 96c. A completed top-up is reversed at the operator before the core. The partner refusal
// is a one-shot mock-control scenario on ocs reverse (code 4091, unmapped), cleared afterwards.
export default [
  {
    name: '[topup reversal frs] a completed top-up is reversed at the operator before the core and every balance is restored',
    run: async (MW, S) => {
      await S.note(['01711000001', 'SETTLEMENT', 'FEE_INCOME']);
      await S.topup('customer01', 'gp', 'PREPAID', '01711000002', 2000, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      const ocsAt = await MW.sim.lastSeq('ocs');
      const coreAt = (await MW.mockJournal('core')).reduce((m, r) => Math.max(m, r.seq || 0), 0);
      await MW.testApi.reverse(S.txnId());
      await S.appShows('REVERSED', 30);
      await S.noChange();
      const ocs = (await MW.mockJournal('ocs')).filter(r => (r.seq || 0) > ocsAt && String(r.path || '').indexOf('/reverse') >= 0);
      const core = (await MW.mockJournal('core', { path: 'reversal' })).filter(r => (r.seq || 0) > coreAt);
      S.check('the operator was reversed once', ocs.length === 1, ocs);
      S.check('the core was reversed once', core.length === 1, core);
      await S.auditStates('RECEIVED, VALIDATING, HOLDING, PARTNER_PENDING, COMMITTING, COMPLETED, REVERSING, REVERSED');
    },
  },
  {
    name: '[topup reversal frs] a partner refusal to reverse a completed top-up ends in manual review and keeps the posting',
    run: async (MW, S) => {
      S.after(() => MW.mockReset('ocs'));
      await S.note(['01711000001', 'SETTLEMENT', 'FEE_INCOME']);
      await S.topup('customer01', 'gp', 'PREPAID', '01711000002', 2000, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      const coreAt = (await MW.mockJournal('core')).reduce((m, r) => Math.max(m, r.seq || 0), 0);
      await MW.mockScenario('ocs', 'reverse', 'BUSINESS_ERROR', { code: '4091', nextN: 1 });
      await MW.testApi.reverse(S.txnId());
      await S.appShows('MANUAL_REVIEW', 30);
      await S.changedBy({ '01711000001': -2200, 'SETTLEMENT': 2000, 'FEE_INCOME': 174 });
      const core = (await MW.mockJournal('core', { path: 'reversal' })).filter(r => (r.seq || 0) > coreAt);
      S.check('the core was not reversed', core.length === 0, core);
      const rows = await S.auditStates('RECEIVED, VALIDATING, HOLDING, PARTNER_PENDING, COMMITTING, COMPLETED, REVERSING, MANUAL_REVIEW');
      const mr = rows.find(e => e.after && e.after.status === 'MANUAL_REVIEW');
      S.eq('MANUAL_REVIEW carries MW-PTR-GP-UNMAPPED', mr.after.errorCode, 'MW-PTR-GP-UNMAPPED', mr);
    },
  },
];

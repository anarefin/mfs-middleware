// Port of admin-maker-checker.feature: ops-e2e-maker01 (ops-maker) asks, ops-e2e-checker01
// (ops-checker) approves or rejects; every decision is in the transaction's audit trail.
export default [
  {
    name: '[admin topup] a top-up in MANUAL_REVIEW is force-committed after a second operator approves',
    run: async (MW, S) => {
      await S.note(['01711000001', 'SETTLEMENT', 'FEE_INCOME']);
      await S.reject('CommitHold', 'INJECTED_REJECT');
      // A refused commit of a paid top-up sends partner REVERSE (ticket 96b) and ends REVERSED
      // when the partner confirms. Hold that reverse unanswered so the saga stops in MANUAL_REVIEW.
      await MW.mock.clear('ocs');
      S.after(() => MW.mock.clear('ocs'));
      await MW.mock.scenario('ocs', { operation: 'reverse', behaviour: 'TIMEOUT', delayMs: 60000 });
      await S.topup('customer01', 'gp', 'PREPAID', '01711000002', 2000, MW.pin);
      await S.appShows('MANUAL_REVIEW', 30);
      S.crFrom(await MW.admin.ask('ops-e2e-maker01', S.txnId(), 'FORCE_COMMIT', 'OCS confirmed the top-up'));
      await S.crIs('PENDING');
      S.adminAnswers(await MW.admin.approve('ops-e2e-maker01', S.crId), 403, 'MW-AUTH-002');
      await S.adminShows('MANUAL_REVIEW', 5);
      await MW.admin.approve('ops-e2e-checker01', S.crId);
      await S.crIs('APPROVED');
      await S.appShows('COMPLETED', 30);
      await S.changedBy({ '01711000001': -2200, 'SETTLEMENT': 2000, 'FEE_INCOME': 174 });
      await S.noHold('01711000001');
      await S.auditDecision('APPROVED');
    },
  },
  {
    name: '[admin reversal] a completed P2P is reversed after a second operator approves',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE_INCOME']);
      await S.p2p('customer01', '01711000002', 1000, MW.pin);
      S.status(200);
      S.crFrom(await MW.admin.ask('ops-e2e-maker01', S.txnId(), 'REVERSE', 'customer complaint, wrong number'));
      await MW.admin.approve('ops-e2e-checker01', S.crId);
      await S.crIs('APPROVED');
      await S.appShows('REVERSED', 30);
      await S.noChange();
      await S.auditDecision('APPROVED');
    },
  },
  {
    name: '[admin reversal] a rejected reversal changes nothing',
    run: async (MW, S) => {
      await S.p2p('customer01', '01711000002', 1000, MW.pin);
      S.status(200);
      S.crFrom(await MW.admin.ask('ops-e2e-maker01', S.txnId(), 'REVERSE', 'customer complaint'));
      await MW.admin.reject('ops-e2e-checker01', S.crId, 'transfer was correct');
      await S.crIs('REJECTED');
      await S.auditDecision('REJECTED');
      await S.adminShows('COMPLETED', 5);
      S.adminAnswers(await MW.admin.approve('ops-e2e-checker01', S.crId), 409, 'MW-VAL-033');
    },
  },
];

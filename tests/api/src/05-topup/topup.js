// Port of topup.feature. Hold, OCS (mw-simulators) through mw-adapter-telco, then commit or release.
// The OCS refuses MSISDNs ending 999; an amount with fraction .13 is answered past the adapter's
// limit (UNKNOWN, resolved by status inquiry).
export default [
  {
    name: '[topup] a successful top-up debits the payer once and leaves nothing held',
    run: async (MW, S) => {
      await S.note(['01711000001', 'SETTLEMENT', 'FEE-POOL']);
      await S.topup('customer01', 'gp', 'PREPAID', '01711000002', 2000, '1234');
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.changedBy({ '01711000001': -2200, 'SETTLEMENT': 2000, 'FEE-POOL': 200 });
      await S.noHold('01711000001');
      await S.appShows('COMPLETED');
    },
  },
  {
    name: '[topup] an operator refusal releases the hold and moves no money',
    run: async (MW, S) => {
      await S.note(['01711000001', 'SETTLEMENT', 'FEE-POOL']);
      await S.topup('customer01', 'gp', 'PREPAID', '01711000999', 2000, '1234');
      S.status(502);
      S.code('MW-PTR-GP-TOPUP_REJECTED');
      S.carriesTxnId();
      await S.noChange();
      await S.noHold('01711000001');
      await S.appShows('FAILED');
    },
  },
  {
    name: '[topup] a wrong PIN is refused by the core before anything is held',
    run: async (MW, S) => {
      await S.note(['01711000001', 'SETTLEMENT', 'FEE-POOL']);
      await S.topup('customer01', 'gp', 'PREPAID', '01711000002', 2000, '9999');
      S.status(422);
      S.code('MW-CORE-INVALID_PIN');
      await S.noChange();
    },
  },
  {
    name: '[topup] a hold whose reply is lost is found by a status query and released',
    run: async (MW, S) => {
      await S.note(['01711000001', 'SETTLEMENT', 'FEE-POOL']);
      await S.dropReply('HoldFunds');
      await S.topup('customer01', 'gp', 'PREPAID', '01711000002', 2000, '1234');
      S.status(504);
      S.code('MW-TMO-002');
      await S.noChange();
      await S.noHold('01711000001');
      await S.appShows('FAILED');
    },
  },
  {
    name: '[topup] the same idempotency key sent twice tops up once and returns the same txnId',
    run: async (MW, S) => {
      await S.note(['01711000001', 'SETTLEMENT', 'FEE-POOL']);
      await S.topup('customer01', 'gp', 'PREPAID', '01711000002', 2000, '1234');
      await S.repeat();
      S.status(200);
      S.sameTxnId();
      S.txnStatus('COMPLETED');
      await S.changedBy({ '01711000001': -2200, 'SETTLEMENT': 2000, 'FEE-POOL': 200 });
      await S.noHold('01711000001');
    },
  },
  {
    name: '[topup] a top-up without operator is refused by the app API',
    run: async (MW, S) => {
      await S.note(['01711000001', 'SETTLEMENT', 'FEE-POOL']);
      await S.topup('customer01', null, null, '01711000002', 2000, '1234');
      S.status(400);
      S.code('MW-VAL-001');
      await S.noChange();
    },
  },
  {
    name: '[topup tsq] an OCS timeout is resolved by status inquiry and the hold is committed',
    run: async (MW, S) => {
      await S.note(['01711000001', 'SETTLEMENT', 'FEE-POOL']);
      await S.topup('customer01', 'gp', 'PREPAID', '01711000002', 2013, '1234');
      S.status(202);
      S.txnStatus('PROCESSING');
      await S.appShows('COMPLETED', 120);
      await S.changedBy({ '01711000001': -2213, 'SETTLEMENT': 2013, 'FEE-POOL': 200 });
      await S.noHold('01711000001');
    },
  },
  {
    name: '[topup tsq] an OCS timeout the top-up did not survive is resolved by status inquiry and released',
    run: async (MW, S) => {
      await S.note(['01711000001', 'SETTLEMENT', 'FEE-POOL']);
      await S.lateUnapplied('ocs');
      await S.topup('customer01', 'gp', 'PREPAID', '01711000002', 2000, '1234');
      S.status(202);
      S.txnStatus('PROCESSING');
      await S.normalOnceAnswered('ocs', '/ocs/v1/topup');
      await S.appShows('FAILED', 180, 'MW-TMO-003');
      await S.noChange();
      await S.noHold('01711000001');
    },
  },
  {
    name: '[topup tsq] a top-up resolved by status inquiry records UNKNOWN in its audit trail',
    run: async (MW, S) => {
      await S.topup('customer01', 'gp', 'PREPAID', '01711000002', 2013, '1234');
      await S.appShows('COMPLETED', 120);
      await S.auditStates('RECEIVED, VALIDATING, HOLDING, PARTNER_PENDING, UNKNOWN, COMMITTING, COMPLETED');
    },
  },
  {
    name: '[topup] a completed top-up leaves an audit trail of the hold path',
    run: async (MW, S) => {
      await S.topup('customer01', 'gp', 'PREPAID', '01711000002', 2000, '1234');
      S.status(200);
      await S.auditStates('RECEIVED, VALIDATING, HOLDING, PARTNER_PENDING, COMMITTING, COMPLETED');
      await S.auditPartnerCall('mw-adapter-telco');
    },
  },
];

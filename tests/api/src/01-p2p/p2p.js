// Port of p2p.feature. Background: customer01 signed in; balances of 01711000001, 01711000002 and
// FEE_INCOME noted in each scenario (run functions are serialized: no shared constants). The core (mw-core-mock) balances prove that money moves exactly once.
export default [
  {
    name: '[p2p] a P2P completes and moves the money once',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE_INCOME']);
      await S.p2p('customer01', '01711000002', 1000, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.changedBy({ '01711000001': -1500, '01711000002': 1000, 'FEE_INCOME': 435 });
      await S.appShows('COMPLETED');
    },
  },
  {
    // ticket 72: the PIN is checked by mw-auth before anything is published (spec 18.7, D22)
    name: '[p2p] a wrong PIN is refused by mw-auth at once, nothing published, no money moves',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE_INCOME']);
      await S.p2p('customer01', '01711000002', 1000, MW.WRONG_PIN);
      S.status(401);
      S.code('MW-AUTH-040');
      S.eq('mw-auth says how many tries are left', (S.last().body.details || []).map(d => d.field), ['triesLeft']);
      await S.noChange();
      // the right PIN resets the count, so later scenarios start from no failures
      await S.p2p('customer01', '01711000002', 1000, MW.pin);
      S.status(200);
    },
  },
  ...[
    ['over per-txn limit', 600000, 'MW-CORE-LIMIT_EXCEEDED_PER_TXN'],
  ].map(([reason, amount, code]) => ({
    name: `[p2p] a core rejection (${reason}) moves no money`,
    args: { amount, code },
    run: async (MW, S, a) => {
      await S.note(['01711000001', '01711000002', 'FEE_INCOME']);
      await S.p2p('customer01', '01711000002', a.amount, MW.pin);
      S.status(422);
      S.code(a.code);
      S.carriesTxnId();
      await S.noChange();
      await S.appShows('REJECTED');
    },
  })),
  {
    name: '[p2p] the same idempotency key sent twice debits once and returns the same txnId',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE_INCOME']);
      await S.p2p('customer01', '01711000002', 1000, MW.pin);
      await S.repeat();
      S.status(200);
      S.sameTxnId();
      S.txnStatus('COMPLETED');
      await S.changedBy({ '01711000001': -1500, '01711000002': 1000, 'FEE_INCOME': 435 });
    },
  },
  {
    name: '[p2p] the same idempotency key with a different body is refused',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE_INCOME']);
      await S.p2p('customer01', '01711000002', 1000, MW.pin);
      const idem = S.idem;
      await S.submit('customer01', await MW.app.request('P2P', '01711000002', 2000, MW.pin), idem);
      S.status(409);
      S.code('MW-IDEM-001');
      await S.changedBy({ '01711000001': -1500, '01711000002': 1000, 'FEE_INCOME': 435 });
    },
  },
  {
    name: '[p2p] a completed P2P leaves a verifiable audit trail',
    run: async (MW, S) => {
      await S.p2p('customer01', '01711000002', 1000, MW.pin);
      S.status(200);
      await S.auditStates('RECEIVED, VALIDATING, POSTING, COMPLETED');
      await S.chainVerifies();
    },
  },
  {
    name: '[p2p tsq] a transfer whose core reply is lost is found by QueryTransaction and completes',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE_INCOME']);
      await S.dropReply('PostTransfer');
      await S.p2p('customer01', '01711000002', 1000, MW.pin);
      S.status(202);
      S.txnStatus('PROCESSING');
      await S.appShows('COMPLETED', 90);
      await S.changedBy({ '01711000001': -1500, '01711000002': 1000, 'FEE_INCOME': 435 });
    },
  },
  {
    name: '[p2p tsq] a transfer whose core refusal is lost is found by QueryTransaction and fails',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE_INCOME']);
      await S.refusalLost('PostTransfer');
      await S.p2p('customer01', '01711000002', 1000, MW.pin);
      S.status(202);
      S.txnStatus('PROCESSING');
      await S.appShows('FAILED', 90, 'MW-TMO-003');
      await S.noChange();
    },
  },
];

// Port of p2p.feature. Background: customer01 signed in; balances of 01711000001, 01711000002 and
// FEE-POOL noted in each scenario (run functions are serialized: no shared constants). The core (mw-core-mock) balances prove that money moves exactly once.
export default [
  {
    name: '[p2p] a P2P completes and moves the money once',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE-POOL']);
      await S.p2p('customer01', '01711000002', 1000, '1234');
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.changedBy({ '01711000001': -1500, '01711000002': 1000, 'FEE-POOL': 500 });
      await S.appShows('COMPLETED');
    },
  },
  ...[
    ['wrong PIN', 1000, '9999', 'MW-CORE-INVALID_PIN'],
    ['over per-txn limit', 600000, '1234', 'MW-CORE-LIMIT_EXCEEDED_PER_TXN'],
  ].map(([reason, amount, pin, code]) => ({
    name: `[p2p] a core rejection (${reason}) moves no money`,
    args: { amount, pin, code },
    run: async (MW, S, a) => {
      await S.note(['01711000001', '01711000002', 'FEE-POOL']);
      await S.p2p('customer01', '01711000002', a.amount, a.pin);
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
      await S.note(['01711000001', '01711000002', 'FEE-POOL']);
      await S.p2p('customer01', '01711000002', 1000, '1234');
      await S.repeat();
      S.status(200);
      S.sameTxnId();
      S.txnStatus('COMPLETED');
      await S.changedBy({ '01711000001': -1500, '01711000002': 1000, 'FEE-POOL': 500 });
    },
  },
  {
    name: '[p2p] the same idempotency key with a different body is refused',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE-POOL']);
      await S.p2p('customer01', '01711000002', 1000, '1234');
      const idem = S.idem;
      await S.submit('customer01', await MW.app.request('P2P', '01711000002', 2000, '1234'), idem);
      S.status(409);
      S.code('MW-IDEM-001');
      await S.changedBy({ '01711000001': -1500, '01711000002': 1000, 'FEE-POOL': 500 });
    },
  },
  {
    name: '[p2p] a completed P2P leaves a verifiable audit trail',
    run: async (MW, S) => {
      await S.p2p('customer01', '01711000002', 1000, '1234');
      S.status(200);
      await S.auditStates('RECEIVED, VALIDATING, POSTING, COMPLETED');
      await S.chainVerifies();
    },
  },
  {
    name: '[p2p tsq] a transfer whose core reply is lost is found by QueryTransaction and completes',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE-POOL']);
      await S.dropReply('PostTransfer');
      await S.p2p('customer01', '01711000002', 1000, '1234');
      S.status(202);
      S.txnStatus('PROCESSING');
      await S.appShows('COMPLETED', 90);
      await S.changedBy({ '01711000001': -1500, '01711000002': 1000, 'FEE-POOL': 500 });
    },
  },
  {
    name: '[p2p tsq] a transfer whose core refusal is lost is found by QueryTransaction and fails',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE-POOL']);
      await S.refusalLost('PostTransfer');
      await S.p2p('customer01', '01711000002', 1000, '1234');
      S.status(202);
      S.txnStatus('PROCESSING');
      await S.appShows('FAILED', 90, 'MW-TMO-003');
      await S.noChange();
    },
  },
];

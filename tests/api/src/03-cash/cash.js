// Port of cash.feature. Cash in: agent01 (01811000001) pays the customer from its float. Cash out:
// customer01 (01711000001) pays the agent with the CASH_OUT fee. Balances noted per scenario.
export default [
  {
    name: "[cash] an agent cash-in moves the amount from the agent's float to the customer",
    run: async (MW, S) => {
      await S.note(['01811000001', '01711000001', 'FEE-POOL']);
      await S.txn('agent01', 'CASH_IN', '01711000001', 2000, '1234');
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.changedBy({ '01811000001': -2000, '01711000001': 2000, 'FEE-POOL': 0 });
      await S.appShows('COMPLETED');
    },
  },
  {
    name: '[cash] a customer cash-out pays the agent, the fee goes to the pool',
    run: async (MW, S) => {
      await S.note(['01811000001', '01711000001', 'FEE-POOL']);
      await S.txn('customer01', 'CASH_OUT', '8801811000001', 2000, '1234');
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.changedBy({ '01811000001': 2000, '01711000001': -3000, 'FEE-POOL': 1000 });
      await S.appShows('COMPLETED');
    },
  },
  {
    name: "[cash] a cash-in the agent's float cannot cover is rejected and moves no money",
    run: async (MW, S) => {
      await S.note(['01811000001', '01711000001', 'FEE-POOL']);
      await S.reject('ValidateTransaction', 'INSUFFICIENT_BALANCE');
      await S.txn('agent01', 'CASH_IN', '01711000001', 2000, '1234');
      S.status(422);
      S.code('MW-CORE-INSUFFICIENT_BALANCE');
      S.carriesTxnId();
      await S.noChange();
      await S.appShows('REJECTED');
    },
  },
  {
    name: '[cash] a cash-out to a wallet that is not an agent is refused by the core',
    run: async (MW, S) => {
      await S.note(['01811000001', '01711000001', 'FEE-POOL']);
      await S.txn('customer01', 'CASH_OUT', '01711000002', 2000, '1234');
      S.status(422);
      S.code('MW-CORE-INVALID_COUNTERPARTY');
      await S.noChange();
    },
  },
  {
    name: "[cash] the agent's own wallet is never a counterparty",
    run: async (MW, S) => {
      await S.note(['01811000001', '01711000001', 'FEE-POOL']);
      await S.txn('agent01', 'CASH_IN', '01811000001', 2000, '1234');
      S.status(400);
      S.code('MW-VAL-012');
      await S.noChange();
    },
  },
  ...[['customer01', 'CASH_IN', '01711000002'], ['agent01', 'CASH_OUT', '01711000001']].map(([user, type, counterparty]) => ({
    name: `[cash] ${user} may not initiate ${type}`,
    args: { user, type, counterparty },
    run: async (MW, S, a) => {
      await S.note(['01811000001', '01711000001', 'FEE-POOL']);
      await S.txn(a.user, a.type, a.counterparty, 2000, '1234');
      S.status(403);
      S.code('MW-AUTH-002');
      await S.noChange();
    },
  })),
  {
    name: '[cash] an agent cash-in sent twice with the same idempotency key moves the money once',
    run: async (MW, S) => {
      await S.note(['01811000001', '01711000001', 'FEE-POOL']);
      await S.txn('agent01', 'CASH_IN', '01711000001', 2000, '1234');
      await S.repeat();
      S.status(200);
      S.sameTxnId();
      S.txnStatus('COMPLETED');
      await S.changedBy({ '01811000001': -2000, '01711000001': 2000, 'FEE-POOL': 0 });
    },
  },
  {
    name: '[cash] a customer cash-out sent twice with the same idempotency key moves the money once',
    run: async (MW, S) => {
      await S.note(['01811000001', '01711000001', 'FEE-POOL']);
      await S.txn('customer01', 'CASH_OUT', '8801811000001', 2000, '1234');
      await S.repeat();
      S.status(200);
      S.sameTxnId();
      S.txnStatus('COMPLETED');
      await S.changedBy({ '01811000001': 2000, '01711000001': -3000, 'FEE-POOL': 1000 });
    },
  },
  {
    name: '[cash tsq] a cash-in whose core reply is lost is found by QueryTransaction and completes',
    run: async (MW, S) => {
      await S.note(['01811000001', '01711000001', 'FEE-POOL']);
      await S.dropReply('PostTransfer');
      await S.txn('agent01', 'CASH_IN', '01711000001', 2000, '1234');
      S.status(202);
      await S.appShows('COMPLETED', 90);
      await S.changedBy({ '01811000001': -2000, '01711000001': 2000, 'FEE-POOL': 0 });
    },
  },
  {
    name: '[cash tsq] a cash-in whose core refusal is lost is found by QueryTransaction and fails',
    run: async (MW, S) => {
      await S.note(['01811000001', '01711000001', 'FEE-POOL']);
      await S.refusalLost('PostTransfer');
      await S.txn('agent01', 'CASH_IN', '01711000001', 2000, '1234');
      S.status(202);
      await S.appShows('FAILED', 90, 'MW-TMO-003');
      await S.noChange();
    },
  },
  {
    name: '[cash tsq] a cash-out whose core reply is lost is found by QueryTransaction and completes',
    run: async (MW, S) => {
      await S.note(['01811000001', '01711000001', 'FEE-POOL']);
      await S.dropReply('PostTransfer');
      await S.txn('customer01', 'CASH_OUT', '8801811000001', 2000, '1234');
      S.status(202);
      await S.appShows('COMPLETED', 90);
      await S.changedBy({ '01811000001': 2000, '01711000001': -3000, 'FEE-POOL': 1000 });
    },
  },
  {
    name: '[cash tsq] a cash-out whose core refusal is lost is found by QueryTransaction and fails',
    run: async (MW, S) => {
      await S.note(['01811000001', '01711000001', 'FEE-POOL']);
      await S.refusalLost('PostTransfer');
      await S.txn('customer01', 'CASH_OUT', '8801811000001', 2000, '1234');
      S.status(202);
      await S.appShows('FAILED', 90, 'MW-TMO-003');
      await S.noChange();
    },
  },
];

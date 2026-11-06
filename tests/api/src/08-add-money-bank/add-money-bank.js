// Port of add-money-bank.feature. Background: the c2wbank simulator confirms debits by signed
// callback (reset afterwards). Money comes from SETTLEMENT, no fee, nothing is ever held. Accounts
// ending 999 are refused; fraction .13 = no answer within the limit and no callback.
export default [
  {
    name: '[addmoney c2w] a confirmed debit credits the wallet once and holds nothing',
    run: async (MW, S) => {
      S.after(() => MW.sim.reset('c2wbank'));
      await MW.callbacks.bankCallbacks('ONCE');
      await S.note(['01711000001', 'SETTLEMENT', 'FEE-POOL']);
      await S.addMoney('customer01', 'c2wbank', '1234567890123', 50000, '1234');
      await S.appShows('COMPLETED', 25);
      await S.changedBy({ '01711000001': 50000, 'SETTLEMENT': -50000, 'FEE-POOL': 0 });
      await S.noHold('01711000001');
      await S.auditStates('RECEIVED, VALIDATING, PARTNER_PENDING, COMMITTING, COMPLETED', true);
      await S.auditPartnerCall('mw-adapter-bank');
    },
  },
  {
    name: '[addmoney c2w callback] a replayed bank callback has no second effect',
    run: async (MW, S) => {
      S.after(() => MW.sim.reset('c2wbank'));
      await MW.callbacks.bankCallbacks('ONCE');
      await S.note(['01711000001', 'SETTLEMENT', 'FEE-POOL']);
      await S.addMoney('customer01', 'c2wbank', '1234567890123', 51000, '1234');
      await S.appShows('COMPLETED', 25);
      S.eq('the replayed callback was acknowledged', await MW.sim.replay('c2wbank', MW.callbacks.reference(S.txnId())), 200);
      await S.appShows('COMPLETED');
      await S.changedBy({ '01711000001': 51000, 'SETTLEMENT': -51000, 'FEE-POOL': 0 });
      await S.noLateCallback();
      await S.auditStates('RECEIVED, VALIDATING, PARTNER_PENDING, COMMITTING, COMPLETED', true);
    },
  },
  {
    name: '[addmoney c2w callback] a callback the bank sends twice credits the wallet once',
    run: async (MW, S) => {
      S.after(() => MW.sim.reset('c2wbank'));
      await MW.callbacks.bankCallbacks('DUPLICATE');
      await S.note(['01711000001', 'SETTLEMENT', 'FEE-POOL']);
      await S.addMoney('customer01', 'c2wbank', '1234567890123', 52000, '1234');
      await S.appShows('COMPLETED', 25);
      await S.changedBy({ '01711000001': 52000, 'SETTLEMENT': -52000, 'FEE-POOL': 0 });
      await S.noLateCallback();
    },
  },
  {
    name: '[addmoney c2w] a debit the bank refuses fails and moves nothing',
    run: async (MW, S) => {
      S.after(() => MW.sim.reset('c2wbank'));
      await MW.callbacks.bankCallbacks('ONCE');
      await S.note(['01711000001', 'SETTLEMENT', 'FEE-POOL']);
      await S.addMoney('customer01', 'c2wbank', '1234567890999', 50000, '1234');
      await S.appShows('FAILED', 25, 'MW-PTR-C2WBANK-DEBIT_REFUSED');
      await S.noChange();
      await S.noHold('01711000001');
    },
  },
  {
    name: '[addmoney c2w] a wrong PIN is refused by the core and the bank is never asked',
    run: async (MW, S) => {
      S.after(() => MW.sim.reset('c2wbank'));
      await MW.callbacks.bankCallbacks('ONCE');
      await S.note(['01711000001', 'SETTLEMENT', 'FEE-POOL']);
      await S.addMoney('customer01', 'c2wbank', '1234567890123', 50000, '9999');
      await S.appShows('REJECTED', 25, 'MW-CORE-INVALID_PIN');
      await S.noChange();
    },
  },
  {
    name: '[addmoney c2w tsq] a bank timeout is resolved by status inquiry and the wallet is credited',
    run: async (MW, S) => {
      S.after(() => MW.sim.reset('c2wbank'));
      await MW.callbacks.bankCallbacks('ONCE');
      await S.note(['01711000001', 'SETTLEMENT', 'FEE-POOL']);
      await S.addMoney('customer01', 'c2wbank', '1234567890123', 50013, '1234');
      await S.appShows('COMPLETED', 120);
      await S.changedBy({ '01711000001': 50013, 'SETTLEMENT': -50013, 'FEE-POOL': 0 });
      await S.noHold('01711000001');
      await S.auditStates('RECEIVED, VALIDATING, PARTNER_PENDING, UNKNOWN, COMMITTING, COMPLETED', true);
    },
  },
  {
    name: '[addmoney c2w] an add-money request sent twice with the same idempotency key credits the wallet once',
    run: async (MW, S) => {
      S.after(() => MW.sim.reset('c2wbank'));
      await MW.callbacks.bankCallbacks('ONCE');
      await S.note(['01711000001', 'SETTLEMENT', 'FEE-POOL']);
      await S.addMoney('customer01', 'c2wbank', '1234567890123', 53000, '1234');
      await S.repeat();
      S.sameTxnId();
      await S.appShows('COMPLETED', 25);
      await S.changedBy({ '01711000001': 53000, 'SETTLEMENT': -53000, 'FEE-POOL': 0 });
    },
  },
  {
    name: '[addmoney c2w tsq] a bank timeout the debit did not survive is resolved by status inquiry and fails',
    run: async (MW, S) => {
      await MW.callbacks.bankCallbacks('ONCE');
      await S.note(['01711000001', 'SETTLEMENT', 'FEE-POOL']);
      await S.lateUnapplied('c2wbank');
      await S.addMoney('customer01', 'c2wbank', '1234567890123', 50000, '1234');
      await S.normalOnceAnswered('c2wbank', '/c2wbank/v1/debits');
      await S.appShows('FAILED', 180);
      await S.noChange();
      await S.noHold('01711000001');
    },
  },
];

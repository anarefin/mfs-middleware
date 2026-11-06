// Port of notify-flows.feature: success / failure / processing SMS of every flow, email receipts of
// BILL_PAY and ADD_MONEY_BANK to customer01@example.test (GreenMail through /test/mail/*).
export default [
  {
    name: '[p2p notify] a P2P the core refuses tells the sender it failed',
    run: async (MW, S) => {
      await S.reject('ValidateTransaction', 'INSUFFICIENT_BALANCE');
      await S.p2p('customer01', '01711000002', 1000, '1234');
      S.status(422);
      await S.sms('01711000001', '0171****002 নম্বরে ৳10.00 পাঠানো ব্যর্থ হয়েছে। TrxID {txnId}');
    },
  },
  {
    name: '[p2p notify tsq] a P2P whose core reply is lost tells the sender it is processing, then that it was sent',
    run: async (MW, S) => {
      await S.dropReply('PostTransfer');
      await S.p2p('customer01', '01711000002', 1000, '1234');
      S.status(202);
      await S.sms('01711000001', '0171****002 নম্বরে ৳10.00 পাঠানো প্রক্রিয়াধীন। ফলাফল SMS-এ জানানো হবে। TrxID {txnId}', 60);
      await S.sms('01711000001', 'আপনি 0171****002 নম্বরে ৳10.00 পাঠিয়েছেন। ফি ৳5.00। TrxID {txnId}', 90);
    },
  },
  {
    name: '[cash notify] a cash-in notifies the agent and the customer',
    run: async (MW, S) => {
      await S.txn('agent01', 'CASH_IN', '01711000001', 2000, '1234');
      S.status(200);
      await S.sms('01811000001', '0171****001 নম্বরে ৳20.00 ক্যাশ ইন সফল হয়েছে। TrxID {txnId}');
      await S.sms('01711000001', 'এজেন্ট 0181****001 থেকে ৳20.00 ক্যাশ ইন পেয়েছেন। TrxID {txnId}');
    },
  },
  {
    name: '[cash notify] a cash-in the core refuses tells the agent it failed',
    run: async (MW, S) => {
      await S.reject('ValidateTransaction', 'INSUFFICIENT_BALANCE');
      await S.txn('agent01', 'CASH_IN', '01711000001', 2000, '1234');
      S.status(422);
      await S.sms('01811000001', '0171****001 নম্বরে ৳20.00 ক্যাশ ইন ব্যর্থ হয়েছে। TrxID {txnId}');
    },
  },
  {
    name: '[cash notify] a cash-out notifies the customer (with the fee) and the agent',
    run: async (MW, S) => {
      await S.txn('customer01', 'CASH_OUT', '8801811000001', 2000, '1234');
      S.status(200);
      await S.sms('01711000001', 'এজেন্ট 0181****001 থেকে ৳20.00 ক্যাশ আউট সফল হয়েছে। ফি ৳10.00। TrxID {txnId}');
      await S.sms('01811000001', '0171****001 নম্বর থেকে ৳20.00 ক্যাশ আউট পেয়েছেন। TrxID {txnId}');
    },
  },
  {
    name: '[cash notify] a cash-out the core refuses tells the customer it failed',
    run: async (MW, S) => {
      await S.reject('ValidateTransaction', 'INSUFFICIENT_BALANCE');
      await S.txn('customer01', 'CASH_OUT', '8801811000001', 2000, '1234');
      S.status(422);
      await S.sms('01711000001', 'এজেন্ট 0181****001 থেকে ৳20.00 ক্যাশ আউট ব্যর্থ হয়েছে। TrxID {txnId}');
    },
  },
  {
    name: '[topup notify] a top-up notifies the payer and the recharged number',
    run: async (MW, S) => {
      await S.topup('customer01', 'gp', 'PREPAID', '01711000002', 2000, '1234');
      S.status(200);
      await S.sms('01711000001', '0171****002 নম্বরে ৳20.00 রিচার্জ সফল হয়েছে। TrxID {txnId}');
      await S.sms('01711000002', '0171****001 আপনার নম্বরে ৳20.00 রিচার্জ করেছেন। TrxID {txnId}');
    },
  },
  {
    name: '[topup notify] a top-up the operator refuses tells the payer it failed',
    run: async (MW, S) => {
      await S.topup('customer01', 'gp', 'PREPAID', '01711000999', 2000, '1234');
      S.status(502);
      await S.sms('01711000001', '0171****999 নম্বরে ৳20.00 রিচার্জ ব্যর্থ হয়েছে। TrxID {txnId}');
    },
  },
  {
    name: '[topup notify tsq] a top-up the operator answers too late tells the payer it is processing, then that it succeeded',
    run: async (MW, S) => {
      await S.topup('customer01', 'gp', 'PREPAID', '01711000002', 2013, '1234');
      S.status(202);
      await S.sms('01711000001', '0171****002 নম্বরে ৳20.13 রিচার্জ প্রক্রিয়াধীন। ফলাফল SMS-এ জানানো হবে। TrxID {txnId}', 60);
      await S.sms('01711000001', '0171****002 নম্বরে ৳20.13 রিচার্জ সফল হয়েছে। TrxID {txnId}', 120);
    },
  },
  {
    name: '[billpay notify] a bill payment sends an SMS and an email receipt',
    run: async (MW, S) => {
      await S.billPay('customer01', 'DESCO', '1234567', 66700, '1234');
      S.status(200);
      await S.sms('01711000001', 'DESCO বিল (****4567) ৳667.00 পরিশোধ সফল হয়েছে। ফি ৳3.00। TrxID {txnId}');
      await S.mail('customer01@example.test', 'Bits বিল পরিশোধের রসিদ, TrxID {txnId}');
    },
  },
  {
    name: '[billpay notify] a bill payment the aggregator refuses tells the payer it failed, without a receipt',
    run: async (MW, S) => {
      await S.billPay('customer01', 'DESCO', '1234999', 10000, '1234');
      S.status(502);
      await S.sms('01711000001', 'DESCO বিল (****4999) ৳100.00 পরিশোধ ব্যর্থ হয়েছে। TrxID {txnId}');
      await S.noMail('customer01@example.test');
    },
  },
  {
    name: '[billpay notify tsq] a bill payment the aggregator answers too late tells the payer it is processing',
    run: async (MW, S) => {
      await S.billPay('customer01', 'DESCO', '1234567', 10013, '1234');
      S.status(202);
      await S.sms('01711000001', 'DESCO বিল (****4567) ৳100.13 পরিশোধ প্রক্রিয়াধীন। ফলাফল SMS-এ জানানো হবে। TrxID {txnId}', 60);
      await S.appShows('COMPLETED', 120);
      await S.mail('customer01@example.test', 'Bits বিল পরিশোধের রসিদ, TrxID {txnId}');
    },
  },
  {
    name: '[addmoney c2w notify] money added from a bank account sends an SMS and an email receipt',
    run: async (MW, S) => {
      S.after(() => MW.sim.reset('c2wbank'));
      await MW.callbacks.bankCallbacks('ONCE');
      await S.addMoney('customer01', 'c2wbank', '1234567890123', 50000, '1234');
      await S.appShows('COMPLETED', 25);
      await S.sms('01711000001', 'c2wbank অ্যাকাউন্ট ****0123 থেকে আপনার Bits অ্যাকাউন্টে ৳500.00 যোগ হয়েছে। TrxID {txnId}');
      await S.mail('customer01@example.test', 'Bits অ্যাড মানি রসিদ, TrxID {txnId}');
    },
  },
  {
    name: '[addmoney c2w notify] a debit the bank refuses tells the customer it failed',
    run: async (MW, S) => {
      S.after(() => MW.sim.reset('c2wbank'));
      await MW.callbacks.bankCallbacks('ONCE');
      await S.addMoney('customer01', 'c2wbank', '1234567890999', 50000, '1234');
      await S.appShows('FAILED', 25, 'MW-PTR-C2WBANK-DEBIT_REFUSED');
      await S.sms('01711000001', 'c2wbank অ্যাকাউন্ট ****0999 থেকে ৳500.00 যোগ করা ব্যর্থ হয়েছে। TrxID {txnId}');
    },
  },
  {
    name: '[addmoney c2w notify tsq] a bank that does not answer in time tells the customer it is processing',
    run: async (MW, S) => {
      S.after(() => MW.sim.reset('c2wbank'));
      await MW.callbacks.bankCallbacks('ONCE');
      await S.addMoney('customer01', 'c2wbank', '1234567890123', 50013, '1234');
      await S.sms('01711000001', 'c2wbank অ্যাকাউন্ট ****0123 থেকে ৳500.13 যোগ করা প্রক্রিয়াধীন। ফলাফল SMS-এ জানানো হবে। TrxID {txnId}', 60);
      await S.appShows('COMPLETED', 120);
      await S.mail('customer01@example.test', 'Bits অ্যাড মানি রসিদ, TrxID {txnId}');
    },
  },
];

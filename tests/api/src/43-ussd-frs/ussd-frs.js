// Ticket 109c. Walks the seeded menu (main v4). Amounts are taka. Payment has no tariff row.
// Add money uses the same bank account and callback as folder 08.
export const helpers = [
  function ussdPage() {
    return 'Bits\n1 সেন্ড মানি\n2 ক্যাশ আউট\n3 রিচার্জ\n4 বিল পে\n0. পরবর্তী\n00. পেছনে';
  },
];

export default [
  {
    name: '[ussd frs] a payment to a merchant over USSD shows a free fee and moves the money once',
    run: async (MW, S) => {
      await S.note(['01711000001', '01911000001', 'FEE_INCOME']);
      await S.dial('robi', '01711000001', '*269#', '6, 01911000001, 25, 1, ' + MW.pin);
      S.ussdScreens([
        ussdPage(),
        'মার্চেন্টের Bits নম্বর দিন',
        'টাকার পরিমাণ দিন',
        '01911000001 মার্চেন্টে 25.00 টাকা, ফি 0.00?\n1 নিশ্চিত করুন\n2 বাতিল',
        'আপনার পিন দিন',
      ]);
      S.ussdEnds('01911000001 মার্চেন্টকে 25.00 টাকা দেওয়া হয়েছে।');
      await S.changedBy({ '01711000001': -2500, '01911000001': 2500, 'FEE_INCOME': 0 });
    },
  },
  {
    name: '[ussd frs] add money from a bank account over USSD credits the wallet once',
    run: async (MW, S) => {
      S.after(() => MW.sim.reset('c2wbank'));
      await MW.callbacks.bankCallbacks('ONCE');
      await S.note(['01711000001', 'SETTLEMENT', 'FEE_INCOME']);
      await S.dial('robi', '01711000001', '*269#', '7, 1234567890123, 500, 1, ' + MW.pin);
      S.ussdEnds('500.00 টাকা যোগ হয়েছে।');
      await S.changedBy({ '01711000001': 50000, 'SETTLEMENT': -50000, 'FEE_INCOME': 0 });
      await S.noHold('01711000001');
    },
  },
  {
    name: '[ussd frs] my account shows a mini statement and does not move money',
    run: async (MW, S) => {
      await S.note(['01711000001']);
      await S.dial('robi', '01711000001', '*269#', '8, 2');
      const last = S.session.screens[S.session.screens.length - 1];
      const message = last.message || '';
      // A long history pages (CON, "0. পরবর্তী"); a short one ends. Either screen is the statement.
      S.check('the mini statement is shown', (message.startsWith('সাম্প্রতিক') || message.startsWith('কোনো লেনদেন'))
        && (S.session.ended === true || message.indexOf('0. পরবর্তী') >= 0), S.session.screens);
      await S.noChange();
    },
  },
  {
    name: '[ussd frs] change PIN on USSD stays on the maintenance text and moves nothing',
    run: async (MW, S) => {
      await S.note(['01711000001']);
      await S.dial('robi', '01711000001', '*269#', '8, 3');
      S.ussdEnds('পিন পরিবর্তন এখন বন্ধ।');
      await S.noChange();
    },
  },
  {
    name: '[ussd frs] a remittance reference that is not an Upay txn id says there is none',
    run: async (MW, S) => {
      await S.dial('robi', '01711000001', '*269#', '11, ABC12345');
      S.ussdEnds('এই রেফারেন্সে রেমিট্যান্স নেই।');
    },
  },
];

// Port of ussd.feature. The USSD gateway simulator plays robi over HTTP and POSTs every step to
// mw-ussd through APISIX. Amounts on USSD are in taka (10 Tk = 1000 poisha).
export default [
  {
    name: '[ussd p2p] a P2P over USSD in English completes and moves the money once',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE_INCOME']);
      await S.dial('robi', '01711000001', '*269#', '9, 1, 01711000002, 10, 1, ' + MW.pin);
      S.ussdScreens([
        'Bits\n1 সেন্ড মানি\n2 ক্যাশ আউট\n3 রিচার্জ\n4 বিল পে\n5 ব্যালেন্স\n9 English',
        'Bits\n1 Send Money\n2 Cash Out\n3 Recharge\n4 Pay Bill\n5 Balance\n9 বাংলা',
        'Enter recipient Bits number',
        'Enter amount (Tk)',
        'Send Tk 10.00 to 01711000002?\n1 Confirm\n2 Cancel',
        'Enter your PIN',
      ]);
      S.ussdEnds('Tk 10.00 sent to 01711000002. TxnID ');
      await S.changedBy({ '01711000001': -1500, '01711000002': 1000, 'FEE_INCOME': 435 });
    },
  },
  {
    name: '[ussd p2p] a P2P over USSD in Bangla completes and moves the money once',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE_INCOME']);
      await S.dial('robi', '01711000001', '*269#', '1, 01711000002, 10, 1, ' + MW.pin);
      S.ussdEnds('01711000002 নম্বরে 10.00 টাকা পাঠানো হয়েছে।');
      await S.changedBy({ '01711000001': -1500, '01711000002': 1000, 'FEE_INCOME': 435 });
    },
  },
  {
    name: '[ussd] a bad number is refused and asked again, and no money moves',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE_INCOME']);
      await S.dial('robi', '01711000001', '*269#', '9, 1, 12345');
      S.ussdOpenOn('Enter a valid mobile number.\nEnter recipient Bits number');
      await S.noChange();
    },
  },
  {
    // ticket 72: mw-auth checks the PIN before anything is published; a wrong one asks again
    name: '[ussd] a wrong PIN is refused by mw-auth and asked again, the right one then pays once',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE_INCOME']);
      await S.dial('robi', '01711000001', '*269#', '9, 1, 01711000002, 10, 1, ' + MW.WRONG_PIN);
      S.check('mw-auth refused the PIN and the PIN is asked again (2 tries left)',
        S.session.ended === false && (S.session.screens[S.session.screens.length - 1].message || '').startsWith('Wrong PIN. 2 tries left.'),
        S.session.screens);
      await S.noChange();
      await S.dial('robi', '01711000001', '*269#', '9, 1, 01711000002, 10, 1, ' + MW.WRONG_PIN + ', ' + MW.pin);
      S.ussdEnds('Tk 10.00 sent to 01711000002. TxnID ');
      await S.changedBy({ '01711000001': -1500, '01711000002': 1000, 'FEE_INCOME': 435 });
    },
  },
  {
    // each MNO has its own short code (mw.ussd.mno.<mno>.short-codes): robi *269#, gp *268#
    name: '[ussd] a dial with another MNO\'s code ends at once with the wrong-code screen, and no money moves',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE_INCOME']);
      await S.dial('robi', '01711000001', '*268#', '1, 01711000002, 10, 1, ' + MW.pin);
      S.check('only the dial was answered', S.session.screens.length === 1, S.session.screens);
      S.ussdEnds('এই কোডটি এই নেটওয়ার্কে নেই। ডায়াল করুন *269#');
      await S.noChange();
    },
  },
];

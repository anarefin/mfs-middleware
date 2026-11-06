// Port of ussd.feature. The USSD gateway simulator plays robi over HTTP and POSTs every step to
// mw-ussd through APISIX. Amounts on USSD are in taka (10 Tk = 1000 poisha).
export default [
  {
    name: '[ussd p2p] a P2P over USSD in English completes and moves the money once',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE-POOL']);
      await S.dial('robi', '01711000001', '*268#', '9, 1, 01711000002, 10, 1, 1234');
      S.ussdScreens([
        'Bits\n1 সেন্ড মানি\n2 ক্যাশ আউট\n3 মোবাইল রিচার্জ\n4 বিল পে\n9 English',
        'Bits\n1 Send Money\n2 Cash Out\n3 Mobile Recharge\n4 Pay Bill\n9 বাংলা',
        'Enter recipient Bits number',
        'Enter amount (Tk)',
        'Send Tk 10.00 to 01711000002?\n1 Confirm\n2 Cancel',
        'Enter your PIN',
      ]);
      S.ussdEnds('Tk 10.00 sent to 01711000002. TxnID ');
      await S.changedBy({ '01711000001': -1500, '01711000002': 1000, 'FEE-POOL': 500 });
    },
  },
  {
    name: '[ussd p2p] a P2P over USSD in Bangla completes and moves the money once',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE-POOL']);
      await S.dial('robi', '01711000001', '*268#', '1, 01711000002, 10, 1, 1234');
      S.ussdEnds('01711000002 নম্বরে 10.00 টাকা পাঠানো হয়েছে।');
      await S.changedBy({ '01711000001': -1500, '01711000002': 1000, 'FEE-POOL': 500 });
    },
  },
  {
    name: '[ussd] a bad number is refused and asked again, and no money moves',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE-POOL']);
      await S.dial('robi', '01711000001', '*268#', '9, 1, 12345');
      S.ussdOpenOn('Enter a valid mobile number.\nEnter recipient Bits number');
      await S.noChange();
    },
  },
  {
    name: '[ussd] a wrong PIN is refused by the core and no money moves',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE-POOL']);
      await S.dial('robi', '01711000001', '*268#', '9, 1, 01711000002, 10, 1, 9999');
      S.ussdEnds('Send Money failed: ');
      await S.noChange();
    },
  },
];

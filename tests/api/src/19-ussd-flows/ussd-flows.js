// Port of ussd-flows.feature: cash out, top-up (own number = dialling MNO, other number + operator
// list) and bill pay (category first, then its billers sorted by code, amount due fetched) over USSD,
// robi on HTTP.
export default [
  {
    name: '[ussd topup] a top-up of the own number over USSD in English completes',
    run: async (MW, S) => {
      await S.note(['01711000001', '01811000001', 'SETTLEMENT', 'FEE_INCOME']);
      await S.dial('robi', '01711000001', '*269#', '9, 3, 1, 20, 1, ' + MW.pin);
      S.ussdScreens([
        'Bits\n1 সেন্ড মানি\n2 ক্যাশ আউট\n3 রিচার্জ\n4 বিল পে\n0. পরবর্তী\n00. পেছনে',
        'Bits\n1 Send Money\n2 Cash Out\n3 Recharge\n4 Pay Bill\n0. Next\n00. Back',
        'Mobile Recharge\n1 Own number\n2 Other number',
        'Enter recharge amount (Tk)',
        'Recharge Tk 20.00 to 01711000001?\nFee Tk 2.00\n1 Confirm\n2 Cancel',
        'Enter your PIN',
      ]);
      S.ussdEnds('Tk 20.00 recharged to 01711000001. TxnID ');
      await S.changedBy({ '01711000001': -2200, '01811000001': 0, 'SETTLEMENT': 2000, 'FEE_INCOME': 174 });
      await S.noHold('01711000001');
    },
  },
  {
    name: '[ussd topup] a top-up of another number over USSD in Bangla completes',
    run: async (MW, S) => {
      await S.note(['01711000001', '01811000001', 'SETTLEMENT', 'FEE_INCOME']);
      await S.dial('robi', '01711000001', '*269#', '3, 2, 01711000002, 1, 20, 1, ' + MW.pin);
      S.ussdEnds('01711000002 নম্বরে 20.00 টাকা রিচার্জ হয়েছে।');
      await S.changedBy({ '01711000001': -2200, '01811000001': 0, 'SETTLEMENT': 2000, 'FEE_INCOME': 174 });
    },
  },
  {
    name: '[ussd topup] an operator refusal over USSD moves no money',
    run: async (MW, S) => {
      await S.note(['01711000001', '01811000001', 'SETTLEMENT', 'FEE_INCOME']);
      await S.dial('robi', '01711000001', '*269#', '9, 3, 2, 01711000999, 1, 20, 1, ' + MW.pin);
      S.ussdEnds('Recharge failed: ');
      await S.noChange();
      await S.noHold('01711000001');
    },
  },
  {
    name: '[ussd billpay] a bill pay over USSD in English fetches the bill and pays it',
    run: async (MW, S) => {
      await S.note(['01711000001', '01811000001', 'SETTLEMENT', 'FEE_INCOME']);
      // Ticket 113b: the catalogue projection (57 dev billers, categories UTILITY and OTHER); DESCO is the
      // 6th UTILITY biller by code and is asked by its field label.
      await S.dial('robi', '01711000001', '*269#', '9, 4, 1, 6, 1234567, 1, ' + MW.pin);
      S.ussdScreens([
        'Bits\n1 সেন্ড মানি\n2 ক্যাশ আউট\n3 রিচার্জ\n4 বিল পে\n0. পরবর্তী\n00. পেছনে',
        'Bits\n1 Send Money\n2 Cash Out\n3 Recharge\n4 Pay Bill\n0. Next\n00. Back',
        'Select category\n1 Utility\n2 Other',
        'Select biller\n1 BGDCL\n2 BPDB\n3 BREB\n4 BTCL\n5 CWASA\n6 DESCO\n7 DESCO_PRE\n8 DPDC\n9 JGTDSL\n10 KGDCL'
          + '\n11 KWASA\n12 LINK3\n13 NESCO\n14 PGCL\n15 TITAS\n16 WASA\n17 WZPDCL',
        'Enter Account number',
        'Pay Tk 667.00 for DESCO bill 1234567?\nFee Tk 3.00\n1 Confirm\n2 Cancel',
        'Enter your PIN',
      ]);
      S.ussdEnds('Tk 667.00 paid for DESCO bill 1234567. TxnID ');
      await S.changedBy({ '01711000001': -67000, '01811000001': 0, 'SETTLEMENT': 66700, 'FEE_INCOME': 261 });
      await S.noHold('01711000001');
    },
  },
  {
    name: '[ussd billpay] a bill pay over USSD in Bangla completes',
    run: async (MW, S) => {
      await S.note(['01711000001', '01811000001', 'SETTLEMENT', 'FEE_INCOME']);
      await S.dial('robi', '01711000001', '*269#', '4, 1, 6, 1234567, 1, ' + MW.pin);
      S.ussdEnds('DESCO বিল 1234567: 667.00 টাকা দেওয়া হয়েছে।');
      await S.changedBy({ '01711000001': -67000, '01811000001': 0, 'SETTLEMENT': 66700, 'FEE_INCOME': 261 });
    },
  },
  {
    name: '[ussd billpay] an unknown bill number is refused and asked again, and no money moves',
    run: async (MW, S) => {
      await S.note(['01711000001', '01811000001', 'SETTLEMENT', 'FEE_INCOME']);
      await S.dial('robi', '01711000001', '*269#', '9, 4, 1, 6, 1234404');
      S.ussdOpenEndingWith('\nEnter Account number');
      await S.noChange();
    },
  },
  {
    name: '[ussd cash] a customer cash-out to an agent over USSD completes',
    run: async (MW, S) => {
      await S.note(['01711000001', '01811000001', 'SETTLEMENT', 'FEE_INCOME']);
      await S.dial('robi', '01711000001', '*269#', '9, 2, 01811000001, 50, 1, ' + MW.pin);
      S.ussdEnds('Tk 50.00 cashed out at agent 01811000001. TxnID ');
      await S.changedBy({ '01711000001': -6000, '01811000001': 5000, 'SETTLEMENT': 0, 'FEE_INCOME': 435 });
    },
  },
];

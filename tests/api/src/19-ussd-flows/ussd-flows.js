// Port of ussd-flows.feature: cash out, top-up (own number = dialling MNO, other number + operator
// list) and bill pay (billers sorted by code, amount due fetched) over USSD, robi on HTTP.
export default [
  {
    name: '[ussd topup] a top-up of the own number over USSD in English completes',
    run: async (MW, S) => {
      await S.note(['01711000001', '01811000001', 'SETTLEMENT', 'FEE-POOL']);
      await S.dial('robi', '01711000001', '*268#', '9, 3, 1, 20, 1, 1234');
      S.ussdScreens([
        'Bits\n1 সেন্ড মানি\n2 ক্যাশ আউট\n3 মোবাইল রিচার্জ\n4 বিল পে\n9 English',
        'Bits\n1 Send Money\n2 Cash Out\n3 Mobile Recharge\n4 Pay Bill\n9 বাংলা',
        'Mobile Recharge\n1 Own number\n2 Other number',
        'Enter recharge amount (Tk)',
        'Recharge Tk 20.00 to 01711000001?\n1 Confirm\n2 Cancel',
        'Enter your PIN',
      ]);
      S.ussdEnds('Tk 20.00 recharged to 01711000001. TxnID ');
      await S.changedBy({ '01711000001': -2200, '01811000001': 0, 'SETTLEMENT': 2000, 'FEE-POOL': 200 });
      await S.noHold('01711000001');
    },
  },
  {
    name: '[ussd topup] a top-up of another number over USSD in Bangla completes',
    run: async (MW, S) => {
      await S.note(['01711000001', '01811000001', 'SETTLEMENT', 'FEE-POOL']);
      await S.dial('robi', '01711000001', '*268#', '3, 2, 01711000002, 1, 20, 1, 1234');
      S.ussdEnds('01711000002 নম্বরে 20.00 টাকা রিচার্জ হয়েছে।');
      await S.changedBy({ '01711000001': -2200, '01811000001': 0, 'SETTLEMENT': 2000, 'FEE-POOL': 200 });
    },
  },
  {
    name: '[ussd topup] an operator refusal over USSD moves no money',
    run: async (MW, S) => {
      await S.note(['01711000001', '01811000001', 'SETTLEMENT', 'FEE-POOL']);
      await S.dial('robi', '01711000001', '*268#', '9, 3, 2, 01711000999, 1, 20, 1, 1234');
      S.ussdEnds('Recharge failed: ');
      await S.noChange();
      await S.noHold('01711000001');
    },
  },
  {
    name: '[ussd billpay] a bill pay over USSD in English fetches the bill and pays it',
    run: async (MW, S) => {
      await S.note(['01711000001', '01811000001', 'SETTLEMENT', 'FEE-POOL']);
      await S.dial('robi', '01711000001', '*268#', '9, 4, 1, 1234567, 1, 1234');
      S.ussdScreens([
        'Bits\n1 সেন্ড মানি\n2 ক্যাশ আউট\n3 মোবাইল রিচার্জ\n4 বিল পে\n9 English',
        'Bits\n1 Send Money\n2 Cash Out\n3 Mobile Recharge\n4 Pay Bill\n9 বাংলা',
        'Select biller\n1 DESCO\n2 DPDC\n3 TITAS\n4 WASA',
        'Enter DESCO bill number',
        'Pay Tk 667.00 for DESCO bill 1234567?\n1 Confirm\n2 Cancel',
        'Enter your PIN',
      ]);
      S.ussdEnds('Tk 667.00 paid for DESCO bill 1234567. TxnID ');
      await S.changedBy({ '01711000001': -67000, '01811000001': 0, 'SETTLEMENT': 66700, 'FEE-POOL': 300 });
      await S.noHold('01711000001');
    },
  },
  {
    name: '[ussd billpay] a bill pay over USSD in Bangla completes',
    run: async (MW, S) => {
      await S.note(['01711000001', '01811000001', 'SETTLEMENT', 'FEE-POOL']);
      await S.dial('robi', '01711000001', '*268#', '4, 1, 1234567, 1, 1234');
      S.ussdEnds('DESCO বিল 1234567: 667.00 টাকা দেওয়া হয়েছে।');
      await S.changedBy({ '01711000001': -67000, '01811000001': 0, 'SETTLEMENT': 66700, 'FEE-POOL': 300 });
    },
  },
  {
    name: '[ussd billpay] an unknown bill number is refused and asked again, and no money moves',
    run: async (MW, S) => {
      await S.note(['01711000001', '01811000001', 'SETTLEMENT', 'FEE-POOL']);
      await S.dial('robi', '01711000001', '*268#', '9, 4, 1, 1234404');
      S.ussdOpenEndingWith('\nEnter DESCO bill number');
      await S.noChange();
    },
  },
  {
    name: '[ussd cash] a customer cash-out to an agent over USSD completes',
    run: async (MW, S) => {
      await S.note(['01711000001', '01811000001', 'SETTLEMENT', 'FEE-POOL']);
      await S.dial('robi', '01711000001', '*268#', '9, 2, 01811000001, 50, 1, 1234');
      S.ussdEnds('Tk 50.00 cashed out at agent 01811000001. TxnID ');
      await S.changedBy({ '01711000001': -6000, '01811000001': 5000, 'SETTLEMENT': 0, 'FEE-POOL': 1000 });
    },
  },
];

// Port of ussd-smpp.feature. gp's USSD runs over SMPP: mw-ussd holds transceiver binds to the
// USSD gateway simulator. Background: mw-ussd is bound (binds and refusals noted).
export default [
  {
    name: '[ussd smpp p2p] a P2P over SMPP USSD walks the same menu and moves the money once',
    run: async (MW, S) => {
      await bound(MW, S);
      await S.note(['01711000001', '01711000002', 'FEE-POOL']);
      await S.dial('gp', '01711000001', '*268#', '9, 1, 01711000002, 10, 1, 1234');
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
    name: '[ussd smpp] a bad number is asked again over SMPP and no money moves',
    run: async (MW, S) => {
      await bound(MW, S);
      await S.note(['01711000001', '01711000002', 'FEE-POOL']);
      await S.dial('gp', '01711000001', '*268#', '9, 1, 12345');
      S.ussdOpenOn('Enter a valid mobile number.\nEnter recipient Bits number');
      await S.noChange();
    },
  },
  {
    name: '[ussd smpp] the binds are kept alive with enquire_link',
    run: async (MW, S) => {
      await bound(MW, S);
      const before = (await MW.sim.smppBinds()).enquireLinks;
      // enquire_link every 30 s per bind
      const b = await MW.poll(() => MW.sim.smppBinds(), x => x.enquireLinks > before, 45000, 1000);
      S.check('mw-ussd keeps its SMPP binds alive with enquire_link', b.enquireLinks > before, b);
    },
  },
  {
    name: '[ussd smpp] after the gateway drops the binds mw-ussd reconnects and USSD works again',
    run: async (MW, S) => {
      await bound(MW, S);
      await MW.sim.smppDrop();
      const b = await MW.poll(() => MW.sim.smppBinds(), x => x.binds > S.bindsBefore && x.bound >= 1, 90000, 1000);
      S.check('mw-ussd binds to the SMPP USSD gateway again', b.binds > S.bindsBefore && b.bound >= 1, b);
      await S.dial('gp', '01711000001', '*268#', '9, 1');
      S.ussdOpenOn('Enter recipient Bits number');
    },
  },
  {
    name: '[ussd smpp] throttling, queue full and generic_nack are retried after a delay',
    run: async (MW, S) => {
      await bound(MW, S);
      S.after(() => MW.sim.smppScenario({}));
      await MW.sim.smppScenario({ throttleNext: 1, queueFullNext: 1, genericNackNext: 1 });
      await S.dial('gp', '01711000001', '*268#', '9, 1');
      S.ussdOpenOn('Enter recipient Bits number');
      S.eq('the SMPP USSD gateway refused 3 screens', (await MW.sim.smppBinds()).refused - S.refusedBefore, 3);
    },
  },
];

async function bound(MW, S) {
  const b = await MW.poll(() => MW.sim.smppBinds(), x => x.bound >= 1, 90000, 1000);
  S.check('mw-ussd is bound to the SMPP USSD gateway', b.bound >= 1, b);
  S.bindsBefore = b.binds;
  S.refusedBefore = b.refused;
}
export const helpers = [bound];

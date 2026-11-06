// Port of admin-content.feature: a USSD menu or SMS template version through maker-checker,
// published on mw.config.changed.v1. S.token is a random 4-digit marker per scenario, so a re-run
// never reads an earlier run's text. The menu scenario uses bl (no other folder walks it).
export default [
  {
    name: '[admin] an approved menu text change shows in the next USSD session but not in a running one',
    run: async (MW, S) => {
      const open = await MW.sim.ussd('bl', '01711000001', '*268#', []);
      S.check('a session is open on bl', open.ended === false, open.screens);
      const menu = JSON.parse(JSON.stringify(MW.fixture('main-e2e')));
      delete menu.menuId;
      delete menu.version;
      const title = 'Bits ' + S.token;
      menu.nodes.main.text.bn = title;
      menu.nodes.main.text.en = title;
      S.crFrom(await MW.admin.proposeMenu('ops-e2e-maker01', 'main', { menu, activations: [{ mno: 'bl' }], reason: 'api title change ' + S.token }));
      await MW.admin.approve('ops-e2e-checker01', S.crId);
      await S.crIs('APPROVED');
      const want = title + '\n1 সেন্ড মানি';
      const fresh = await MW.poll(() => MW.sim.ussd('bl', '01711000003', '*268#', []),
        s => { const l = s.screens[s.screens.length - 1]; return !!l.message && l.message.startsWith(want); }, 30000, 1000);
      const first = fresh.screens[fresh.screens.length - 1].message || '';
      S.check('a new USSD session of 01711000003 on bl starts with the new title', first.startsWith(want), first);
      const cont = await MW.sim.ussd('bl', '01711000001', '*268#', ['9'], open.sessionId);
      const last = cont.screens[cont.screens.length - 1];
      S.check('the open session shows the English main menu of the version it started on', last.status === 200
        && (last.message || '').includes('\n1 Send Money') && !(last.message || '').includes(title), cont.screens);
    },
  },
  {
    name: '[admin p2p] an approved SMS template change is used for the next notification',
    run: async (MW, S) => {
      const propose = async text => {
        S.crFrom(await MW.admin.proposeTemplate('ops-e2e-maker01', 'TXN_P2P_RECEIVED', 'SMS', 'en', { body: text, senderId: 'Bits', reason: 'api wording ' + S.token }));
        await MW.admin.approve('ops-e2e-checker01', S.crId);
        await S.crIs('APPROVED');
      };
      S.after(() => propose('You have received Tk {{amount}} from {{counterparty}}. TrxID {{txnId}}').catch(() => undefined));
      await propose('Bits ' + S.token + ': you got Tk {{amount}} from {{counterparty}}. TrxID {{txnId}}');
      // Cucumber slept 5 s for mw-notify to take the version; here the P2P is repeated until its SMS
      // uses it (the first one normally does)
      const prefix = 'Bits ' + S.token + ': you got Tk 10.00 from 0171****001. TrxID ';
      let got = [];
      for (let attempt = 0; attempt < 4 && !got.length; attempt++) {
        await S.dial('robi', '01711000001', '*268#', '9, 1, 01711000002, 10, 1, 1234');
        S.ussdEnds('Tk 10.00 sent to 01711000002. TxnID ');
        got = await MW.poll(async () => (await MW.sim.sms('01711000002')).filter(m => (m.text || '').startsWith(prefix) && m.dlrStatus),
          l => l.length > 0, 10000, 1000);
      }
      S.check('01711000002 gets an SMS starting with "' + prefix + '", delivered', got.length > 0 && got[0].dlrStatus === 'DELIVRD', got);
    },
  },
];

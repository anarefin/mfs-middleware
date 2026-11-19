// Tickets 102a/102b (ADR D33, FRS MCT-01..04, MCT-06): mw-simulators' mock-control API through the
// test-only route /test/mock-control/*. Every scenario puts its party back to normal afterwards.
export const helpers = [
  async function boundUssd(MW, S) {
    const b = await MW.poll(() => MW.sim.smppBinds(), x => x.bound >= 1, 90000, 1000);
    S.check('mw-ussd is bound to the SMPP USSD gateway', b.bound >= 1, b);
    S.refusedBefore = b.refused;
  },
];

export default [
  {
    name: '[mock] every mock party is listed with its operations, keys and behaviours',
    run: async (MW, S) => {
      const parties = await MW.mock.parties();
      const names = parties.map(p => p.name);
      for (const p of ['ocs', 'billagg', 'c2wbank', 'merchant', 'smsc', 'ussdgw', 'payshield']) {
        S.check('party ' + p + ' is listed', names.includes(p), names);
      }
      const smsc = parties.find(p => p.name === 'smsc');
      S.eq('the SMSC has the operations bind and submit', smsc.operations, ['bind', 'submit']);
      S.check('the SMSC can leave out its DLR', smsc.behaviours.includes('NO_CALLBACK'), smsc.behaviours);
      S.check('payShield cannot answer HTTP 5xx', !parties.find(p => p.name === 'payshield').behaviours.includes('HTTP_5XX'));
    },
  },
  {
    name: '[mock] a scenario the party cannot carry out is refused and an unknown party is 404',
    run: async (MW, S) => {
      const ask = (party, body) => MW.http('POST', '/test/mock-control/' + party + '/scenarios', { apikey: true, json: body });
      const noOp = await ask('ocs', { operation: 'fly', behaviour: 'TIMEOUT' });
      S.status(400, noOp);
      S.eq('an unknown operation is MW-VAL-001', noOp.body && noOp.body.code, 'MW-VAL-001');
      S.status(400, await ask('payshield', { behaviour: 'HTTP_5XX' }));
      S.status(400, await ask('smsc', { operation: 'submit', behaviour: 'BUSINESS_ERROR', code: 'x58' }));
      S.status(404, await ask('nobody', { behaviour: 'SUCCESS' }));
    },
  },
  {
    name: '[mock topup] a key-scoped BUSINESS_ERROR on ocs refuses that number only; the magic values decide the rest',
    run: async (MW, S) => {
      S.after(() => MW.mock.normal('ocs'));
      await MW.mock.normal('ocs');
      const since = await MW.sim.lastSeq('ocs');
      const s = await MW.mock.scenario('ocs', { operation: 'topup', behaviour: 'BUSINESS_ERROR', key: '01711000003' });
      S.check('the scenario has an id', !!s.id, s);
      await S.note(['01711000001', 'SETTLEMENT', 'FEE_INCOME']);
      await S.topup('customer01', 'gp', 'PREPAID', '01711000003', 2000, MW.pin);
      S.status(502);
      S.code('MW-PTR-GP-TOPUP_REJECTED');
      await S.noChange();
      await S.noHold('01711000001');
      const seen = await MW.mock.journal('ocs', { contains: '01711000003', afterSeq: since });
      S.check('the journal holds the refused top-up', seen.some(r => r.path === '/ocs/v1/topup'), seen);
      await S.topup('customer01', 'gp', 'PREPAID', '01711000002', 2000, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
    },
  },
  {
    name: '[mock p2p notify smpp] the smsc journal records the SMS submits; a NO_CALLBACK scenario leaves out that DLR',
    run: async (MW, S) => {
      S.after(() => MW.mock.normal('smsc'));
      await MW.mock.normal('smsc');
      const since = await MW.sim.lastSeq('smsc');
      await MW.mock.scenario('smsc', { operation: 'submit', behaviour: 'NO_CALLBACK', key: '01711000002' });
      await S.p2p('customer01', '01711000002', 1000, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      const id = S.txnId();
      const mine = async w => (await MW.sim.sms(w)).filter(m => (m.text || '').includes(id));
      const sent = await MW.poll(() => mine('01711000001'), l => l.length > 0 && !!l[0].dlrStatus, 30000, 500);
      S.eq('the sender\'s SMS is delivered (DLR)', sent.length && sent[0].dlrStatus, 'DELIVRD');
      const got = await MW.poll(() => mine('01711000002'), l => l.length > 0, 30000, 500);
      S.eq('the receiver\'s SMS has no DLR', got.length ? got[0].dlrStatus || null : 'no SMS', null, got);
      const j = await MW.mock.journal('smsc', { path: 'submit_sm', afterSeq: since });
      S.check('the journal records the submits to the receiver', j.some(r => r.headers.destination === '01711000002'), j);
      S.check('the journal carries no SMS text', j.every(r => r.body === null));
    },
  },
  {
    name: '[mock ussd smpp] a ussdgw scenario refuses the next screen with ESME_RTHROTTLED and mw-ussd retries it',
    run: async (MW, S) => {
      await boundUssd(MW, S);
      S.after(() => MW.mock.normal('ussdgw'));
      await MW.mock.normal('ussdgw');
      await MW.mock.scenario('ussdgw', { operation: 'submit', behaviour: 'BUSINESS_ERROR', code: '0x58', nextN: 1 });
      await S.dial('gp', '01711000001', '*268#', '9, 1');
      S.ussdOpenOn('Enter recipient Bits number');
      S.eq('the SMPP USSD gateway refused 1 screen', (await MW.sim.smppBinds()).refused - S.refusedBefore, 1);
      S.eq('the next-N scenario is used up', (await MW.mock.scenarios('ussdgw')).length, 0);
    },
  },
];

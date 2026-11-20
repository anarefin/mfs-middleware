// Ticket 118b (FRS UC-13, EXT-14, FL-07, PLT-014). mw-simulators' CyberSourceMock (ticket 118a) is the
// acquirer: POST /cybersource/v1/sessions opens a hosted page, the form POST /cybersource/hp/<sessionId>
// turns the card into a transient token and sends the signed 3-D Secure webhook to the party's callbackUrl
// (through APISIX to mw-adapter-card), the capture answers by the card the customer typed. The wallet is
// credited from SETTLEMENT_CYBERSOURCE only after a capture of its own amount and reference.
// Helpers are copied into each script; a module-level binding is not.
export const helpers = [
  function books() {
    return ['01711000001', 'SETTLEMENT_CYBERSOURCE'];
  },
  function credited(amount) {
    return { '01711000001': amount, 'SETTLEMENT_CYBERSOURCE': -amount };
  },
  /** The simulator's webhook goes through APISIX to mw-adapter-card (route partner-cybersource-callback). */
  async function webhooksTo(MW) {
    await MW.mockReset('cybersource');
    await MW.mock.settings('cybersource', { callbackUrl: 'http://apisix:9080/partner/cybersource/callback/3ds' });
  },
  /** Opens a session for amount and checks the hosted page URL (https, no card field asked of the app). */
  async function open(MW, S, amount, idem) {
    S.user = 'customer01';
    const r = S.record(await MW.app.cardSession('customer01', idem || 'card-' + MW.uuid(), amount, MW.pin));
    S.status(201, r);
    S.check('the answer is the hosted page of the session', !!r.body.sessionId
        && /^https:\/\/[^/]+\/cybersource\/hp\//.test(r.body.hostedPageUrl || ''), r.body);
    return r.body;
  },
  /** The customer types card on the hosted page (the simulator, never the middleware, sees the number). */
  async function pay(MW, S, session, card) {
    const page = await MW.http('GET', '/test/sim/cybersource/hp/' + session.sessionId, { apikey: true });
    S.status(200, page);
    const r = await MW.http('POST', '/test/sim/cybersource/hp/' + session.sessionId, { apikey: true, form: { cardNumber: card } });
    S.check('the hosted page took the card', r.status === 200 || r.status === 201, String(r));
    return r;
  },
];

export default [
  {
    name: '[addmoney callback frs] T1 a card the 3-D Secure check passes is captured and credits the wallet once from the card settlement wallet',
    run: async (MW, S) => {
      await webhooksTo(MW);
      S.after(() => MW.mockReset('cybersource'));
      await S.note(books());
      const session = await open(MW, S, 50000);
      await pay(MW, S, session, '4111111111111111');
      await S.appShows('COMPLETED', 25);
      await S.changedBy(credited(50000));
      const status = S.record(await MW.app.cardStatus('customer01', S.txnId()));
      S.status(200, status);
      S.eq('an ADD_MONEY_CARD', status.body.txnType, 'ADD_MONEY_CARD');
      S.check('no card number in the status', !JSON.stringify(status.body).includes('4111111111111111'), status.body);
    },
  },
  {
    name: '[addmoney frs] T2 a declined card fails with the mapped acquirer error and moves nothing',
    run: async (MW, S) => {
      await webhooksTo(MW);
      S.after(() => MW.mockReset('cybersource'));
      await S.note(books());
      const session = await open(MW, S, 50100);
      await pay(MW, S, session, '4000000000000002');
      await S.appShows('FAILED', 25, 'MW-PTR-CYBERSOURCE-CARD_DECLINED');
      await S.noChange();
    },
  },
  {
    name: '[addmoney callback frs] a 3-D Secure failure fails before any capture and moves nothing',
    run: async (MW, S) => {
      await webhooksTo(MW);
      S.after(() => MW.mockReset('cybersource'));
      await S.note(books());
      const session = await open(MW, S, 50200);
      await pay(MW, S, session, '4000000000003063');
      await S.appShows('FAILED', 25, 'MW-PTR-CYBERSOURCE-THREE_DS_FAILED');
      await S.noChange();
      const captures = await MW.mockJournal('cybersource', { path: 'payments' });
      S.check('no capture was asked for', !captures.some(c => c.method === 'POST' && (c.body || '').includes(session.sessionId)), captures);
    },
  },
  {
    name: '[addmoney tsq frs] T3 a capture timeout is resolved by status inquiry and credits the wallet once',
    run: async (MW, S) => {
      await webhooksTo(MW);
      S.after(() => MW.mockReset('cybersource'));
      await S.note(books());
      const session = await open(MW, S, 50300);
      await pay(MW, S, session, '4000000000000119');
      await MW.waitForState(S.txnId(), ['UNKNOWN', 'COMMITTING', 'COMPLETED'], 40000).catch(() => null);
      const r = await MW.http('POST', '/test/orchestrator/v1/inquiries', { apikey: true, json: { txnId: S.txnId() } });
      S.check('the status inquiry was driven (or the capture was already answered)', r.status === 202 || r.status === 404 || r.status === 409, String(r));
      await S.appShows('COMPLETED', 40);
      await S.changedBy(credited(50300));
    },
  },
  {
    name: '[addmoney frs] T6 the same Idempotency-Key twice opens one session and credits the wallet once',
    run: async (MW, S) => {
      await webhooksTo(MW);
      S.after(() => MW.mockReset('cybersource'));
      await S.note(books());
      const idem = 'card-' + MW.uuid();
      const session = await open(MW, S, 50400, idem);
      const again = S.record(await MW.app.cardSession('customer01', idem, 50400, MW.pin));
      S.check('the repeat names the same transaction', again.body && again.body.txnId === session.txnId, again.body);
      await pay(MW, S, session, '4111111111111111');
      await S.appShows('COMPLETED', 25);
      await S.changedBy(credited(50400));
    },
  },
  {
    name: '[addmoney callback frs] T7 a 3-D Secure webhook sent twice captures and credits once',
    run: async (MW, S) => {
      await webhooksTo(MW);
      await MW.mockScenario('cybersource', 'authenticate', 'DUPLICATE_CALLBACK', { nextN: 1 });
      S.after(() => MW.mockReset('cybersource'));
      await S.note(books());
      const session = await open(MW, S, 50500);
      await pay(MW, S, session, '4111111111111111');
      await S.appShows('COMPLETED', 25);
      await S.changedBy(credited(50500));
      const captures = (await MW.mockJournal('cybersource', { path: 'payments' }))
        .filter(c => c.method === 'POST' && (c.body || '').includes(session.sessionId) && !/refunds/.test(c.path || ''));
      S.eq('one capture', captures.length, 1, captures);
    },
  },
  {
    name: '[addmoney frs] T9 a card amount over the per-transaction limit is refused before any session and moves nothing',
    run: async (MW, S) => {
      await webhooksTo(MW);
      S.after(() => MW.mockReset('cybersource'));
      await S.note(books());
      S.user = 'customer01';
      const r = S.record(await MW.app.cardSession('customer01', 'card-' + MW.uuid(), 999999900, MW.pin));
      S.check('refused by the core before any session', r.status === 422, String(r));
      await S.noChange();
    },
  },
];

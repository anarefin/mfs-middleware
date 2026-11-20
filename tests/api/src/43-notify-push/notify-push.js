// Ticket 110 (FRS EXT-04, NOT-01/02/06, FL-14): the push channel of mw-notify against mw-simulators' push
// mock (mock party `push`). The app registers a fresh device token per scenario; the push mock keeps every
// push it accepts (GET /test/sim/push/messages?token=). FL-14: the SMS always goes, the push is optional.
export default [
  {
    name: '[p2p notify] the sender gets a push with the transaction text next to the SMS',
    run: async (MW, S) => {
      S.after(() => MW.mock.normal('push'));
      await MW.mock.normal('push');
      const token = 'api-push-' + MW.uuid();
      S.status(204, await MW.app.pushToken('customer01', token, 'ANDROID'));
      // the token goes to mw-notify over Kafka: give it a moment before the transaction
      await MW.sleep(1500);
      await S.p2p('customer01', '01711000002', 1000, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      const id = S.txnId();
      const pushes = await MW.poll(() => MW.sim.push(token), l => l.some(p => (p.body || '').includes(id)), 30000, 500);
      const p = pushes.find(x => (x.body || '').includes(id));
      S.check('the push for the transaction reached the push server', !!p, pushes);
      S.eq('the push title is the PUSH_TXN_COMPLETED title (bn)', p && p.title, 'লেনদেন সম্পন্ন');
      S.eq('the push text is the rendered template', p && p.body, 'আপনার ৳10.00 এর P2P সম্পন্ন হয়েছে। TrxID ' + id);
      S.eq('the push carries the txnId for the app', p && p.data && p.data.txnId, id);
      S.eq('the push went to the registered platform', p && p.platform, 'ANDROID');
      await S.sms('01711000001', 'আপনি 0171****002 নম্বরে ৳10.00 পাঠিয়েছেন। ফি ৳5.00। TrxID {txnId}');
    },
  },
  {
    name: '[p2p notify mock] a push server that answers 5xx is retried and stores nothing; the SMS for the same transaction still goes',
    run: async (MW, S) => {
      S.after(() => MW.mock.normal('push'));
      await MW.mock.normal('push');
      const token = 'api-push-' + MW.uuid();
      S.status(204, await MW.app.pushToken('customer01', token, 'IOS'));
      await MW.sleep(1500);
      const since = await MW.sim.lastSeq('push');
      await MW.mock.scenario('push', { operation: 'send', behaviour: 'HTTP_5XX', httpStatus: 503, key: token });
      await S.p2p('customer01', '01711000002', 1000, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.sms('01711000001', 'আপনি 0171****002 নম্বরে ৳10.00 পাঠিয়েছেন। ফি ৳5.00। TrxID {txnId}');
      const calls = await MW.poll(() => MW.mock.journal('push', { contains: token, afterSeq: since }), l => l.length >= 3, 30000, 500);
      S.check('mw-notify retried the push (at least 3 calls for the token)', calls.length >= 3, calls.length);
      S.eq('the push server stored nothing for the token', (await MW.sim.push(token)).length, 0);
    },
  },
  {
    name: '[p2p notify mock] a token the push server does not know gets no push and the SMS still goes',
    run: async (MW, S) => {
      S.after(() => MW.mock.normal('push'));
      await MW.mock.normal('push');
      // magic value of the push mock: a token containing "invalid" is UNREGISTERED (404)
      const token = 'invalid-api-push-' + MW.uuid();
      S.status(204, await MW.app.pushToken('customer01', token, 'ANDROID'));
      await MW.sleep(1500);
      const since = await MW.sim.lastSeq('push');
      await S.p2p('customer01', '01711000002', 1000, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.sms('01711000001', 'আপনি 0171****002 নম্বরে ৳10.00 পাঠিয়েছেন। ফি ৳5.00। TrxID {txnId}');
      const calls = await MW.poll(() => MW.mock.journal('push', { contains: token, afterSeq: since }), l => l.length >= 1, 30000, 500);
      S.eq('one call, never retried (a refusal)', calls.length, 1);
      S.eq('nothing stored for the unknown token', (await MW.sim.push(token)).length, 0);
    },
  },
  {
    name: '[notify] a push token with an unknown platform is refused 400 MW-VAL-001',
    run: async (MW, S) => {
      const r = await MW.app.pushToken('customer01', 'api-push-' + MW.uuid(), 'WINDOWS');
      S.status(400, r);
      S.eq('the code is MW-VAL-001', r.body && r.body.code, 'MW-VAL-001');
    },
  },
];

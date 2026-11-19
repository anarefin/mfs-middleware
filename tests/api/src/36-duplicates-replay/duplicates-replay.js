// Ticket 99. The dev stack runs with the business-duplicate window off (MW_DUPLICATE_WINDOW=0s) so
// the other folders may repeat a payment; the duplicate scenarios switch it on through the
// orchestrator test API and off again afterwards. P2P fee: 500 poisha (mw-core-mock).
export default [
  {
    name: '[p2p] the same payment under a new key within the window asks to confirm (MW-VAL-024); the confirmed resend completes',
    run: async (MW, S) => {
      await MW.testApi.duplicateWindow(60);
      S.after(() => MW.testApi.duplicateWindow(0));
      await S.note(['01711000001', '01711000002', 'FEE-POOL']);
      await S.p2p('customer01', '01711000002', 1234, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');

      await S.p2p('customer01', '01711000002', 1234, MW.pin);
      S.status(400);
      S.code('MW-VAL-024');
      S.eq('the prompt is not retryable', S.last().body.retryable, false);
      await S.changedBy({ '01711000001': -1734, '01711000002': 1234, 'FEE-POOL': 500 });

      // the user says yes: the same body with confirmDuplicate, under the key the prompt left unused
      const confirmed = await MW.app.request('P2P', '01711000002', 1234, MW.pin);
      confirmed.confirmDuplicate = true;
      await S.submit('customer01', confirmed, S.idem);
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.changedBy({ '01711000001': -3468, '01711000002': 2468, 'FEE-POOL': 1000 });
    },
  },
  {
    name: '[p2p] another amount within the window is no duplicate',
    run: async (MW, S) => {
      await MW.testApi.duplicateWindow(60);
      S.after(() => MW.testApi.duplicateWindow(0));
      await S.p2p('customer01', '01711000002', 1235, MW.pin);
      S.status(200);
      await S.p2p('customer01', '01711000002', 1236, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
    },
  },
  {
    name: '[p2p reversal] a key answered 200 replays that very body after a reversal; other content is 409 MW-IDEM-001',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE-POOL']);
      await S.p2p('customer01', '01711000002', 1237, MW.pin);
      S.status(200);
      const first = S.last();
      await MW.testApi.reverse(S.txnId(), 'api stored response');
      await S.appShows('REVERSED', 30);

      await S.repeat();
      S.status(200);
      S.eq('the repeat is the first answer byte for byte', S.last().raw, first.raw);
      S.txnStatus('COMPLETED');

      const other = await MW.app.request('P2P', '01711000002', 1238, MW.pin);
      await S.submit('customer01', other, S.idem);
      S.status(409);
      S.code('MW-IDEM-001');
      await S.noChange(); // the reversal gave everything back; the 409 moved nothing
    },
  },
  {
    name: '[checkout edge] a signed session create replayed with its nonce is refused (MW-AUTH-006)',
    run: async (MW, S) => {
      const body = MW.checkout.sessionBody('API-' + MW.uuid(), 1000);
      const nonce = 'api-nonce-' + MW.uuid();
      const send = () => MW.http('POST', '/checkout/v1/sessions', { headers: MW.checkout.signed('POST', '/checkout/v1/sessions', body, MW.v('checkoutSecret'), nonce), raw: body });
      S.record(await send());
      S.status(201);
      S.record(await send());
      S.status(401);
      S.code('MW-AUTH-006');
    },
  },
  {
    name: '[checkout edge] a signed session create dated 6 minutes ago is refused',
    run: async (MW, S) => {
      const body = MW.checkout.sessionBody('API-' + MW.uuid(), 1000);
      const h = MW.checkout.signed('POST', '/checkout/v1/sessions', body, MW.v('checkoutSecret'), null, new Date(Date.now() - 360000));
      S.record(await MW.http('POST', '/checkout/v1/sessions', { headers: h, raw: body }));
      S.status(401);
    },
  },
  {
    name: '[callback edge] a partner callback replayed with its nonce or a stale timestamp is refused',
    run: async (MW, S) => {
      // a reference never issued: an authentic callback passes the signature and is then 404
      const body = MW.callbacks.body(MW.callbacks.reference(MW.uuid()), 'SUCCESS', '000');
      const nonce = 'api-nonce-' + MW.uuid();
      S.record(await MW.callbacks.send(body, MW.v('billaggSecret'), nonce));
      S.status(404);
      S.record(await MW.callbacks.send(body, MW.v('billaggSecret'), nonce));
      S.status(401);
      S.code('MW-AUTH-001');
      S.record(await MW.callbacks.send(body, MW.v('billaggSecret'), null, Math.floor(Date.now() / 1000) - 360));
      S.status(401);
    },
  },
];

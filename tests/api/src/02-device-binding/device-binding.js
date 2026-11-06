// Port of device-binding.feature. lib.js binds one EC P-256 device per user on first use (binding
// OTP read from the SMSC simulator) and signs every submit; these scenarios replay or tamper.
export default [
  {
    name: '[device p2p] a P2P with a valid signature passes',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE-POOL']);
      await S.p2p('customer01', '01711000002', 1000, '1234');
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.changedBy({ '01711000001': -1500, '01711000002': 1000, 'FEE-POOL': 500 });
    },
  },
  {
    name: '[device p2p] a replayed signed request is rejected and moves no money again',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE-POOL']);
      await S.p2p('customer01', '01711000002', 1000, '1234');
      S.record(await MW.app.send(S.lastSubmit));
      S.status(401);
      S.code('MW-AUTH-006');
      await S.changedBy({ '01711000001': -1500, '01711000002': 1000, 'FEE-POOL': 500 });
    },
  },
  {
    name: '[device p2p] a body changed after signing is rejected',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE-POOL']);
      const signed = await MW.app.prepare('customer01', 'api-' + MW.uuid(), await MW.app.request('P2P', '01711000002', 1000, '1234'));
      const tampered = signed.body.replace('"valueMinor":1000', '"valueMinor":1001');
      S.check('the body was changed', tampered !== signed.body);
      S.record(await MW.app.send({ body: tampered, headers: signed.headers }));
      S.status(401);
      S.code('MW-AUTH-004');
      await S.noChange();
    },
  },
];

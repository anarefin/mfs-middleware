// Ticket 76 (ticket 64 acceptance: PVV generate + verify on the running stack). The PIN block comes
// from the payShield simulator's test helper (/test/sim/payshield/pinblock), as a terminal sharing the
// TPK would make it; the TPK's blob under the simulator's LMK comes from mw-crypto's dev-only test
// API, which runs every operation as the test-only client mw-e2e-crypto (bound to TPK and PVK by
// deploy/dev.env.defaults only), so mw-crypto's caller policy still decides. The account is a test
// number, never a card PAN.
export default [
  {
    name: '[crypto] a PIN block under the TPK gets a PVV from the payShield; it verifies the PIN and refuses another',
    run: async (MW, S) => {
      const account = '4000001234562000';
      const key = S.record(await MW.http('GET', '/test/crypto/v1/payshield/keys/TPK', { apikey: true }));
      S.status(200);
      S.check('the TPK and its blob under the LMK (no clear key)', key.body.purpose === 'TPK' && /^U[0-9A-F]{32}$/.test(key.body.keyBlock), key.body);
      const block = async pin => {
        const r = await MW.must('POST', '/test/sim/payshield/pinblock', { apikey: true,
          json: { keyType: 'TPK', key: key.body.keyBlock, format: 'ISO0', pin, pan: account } });
        return { keyId: key.body.kid, format: 'ISO0', block: r.body.block };
      };
      const right = await block('4820');
      const gen = S.record(await MW.http('POST', '/test/crypto/v1/payshield/pvv/generate', { apikey: true,
        json: { pvkId: 'pvk', pvki: 1, pinBlock: right, account } }));
      S.status(200);
      S.check('a 4-digit PVV under the PVK version', /^[0-9]{4}$/.test(gen.body.pvv) && /^pvk\.v[0-9]+$/.test(gen.body.pvkId), gen.body);
      const verify = async pinBlock => MW.http('POST', '/test/crypto/v1/payshield/pvv/verify', { apikey: true,
        json: { pvkId: gen.body.pvkId, pvki: 1, pinBlock, account, pvv: gen.body.pvv } });
      const ok = S.record(await verify(right));
      S.status(200);
      S.eq('the same PIN matches', ok.body.match, true);
      const other = S.record(await verify(await block('7315')));
      S.status(200);
      S.eq('another PIN does not match (no error code: no oracle)', other.body.match, false);
    },
  },
  {
    name: '[crypto] the test-only client is bound to the TPK and PVK only: a ZPK block is refused, the PVK is never served',
    run: async (MW, S) => {
      const account = '4000001234562000';
      const zpk = S.record(await MW.http('GET', '/test/crypto/v1/payshield/keys/ZPK', { apikey: true }));
      S.status(200);
      const b = await MW.must('POST', '/test/sim/payshield/pinblock', { apikey: true,
        json: { keyType: 'ZPK', key: zpk.body.keyBlock, format: 'ISO0', pin: '4820', pan: account } });
      S.record(await MW.http('POST', '/test/crypto/v1/payshield/pvv/generate', { apikey: true,
        json: { pvkId: 'pvk', pvki: 1, pinBlock: { keyId: zpk.body.kid, format: 'ISO0', block: b.body.block }, account } }));
      S.status(403);
      S.code('MW-AUTH-002');
      S.record(await MW.http('GET', '/test/crypto/v1/payshield/keys/PVK', { apikey: true }));
      S.status(400);
      S.code('MW-VAL-015');
      // the route is behind the test key: no key, no answer
      S.record(await MW.http('GET', '/test/crypto/v1/payshield/keys/TPK'));
      S.status(401);
    },
  },
];

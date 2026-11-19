// Port of device-binding.feature. lib.js binds one EC P-256 device per user on first use at mw-auth
// (POST /auth/v1/otp with the MSISDN, binding OTP read from the SMSC simulator, POST /auth/v1/devices
// with the app PIN; tickets 68-69), logs in there (MSISDN + PIN + device signature, ticket 69) and
// signs every submit, which mw-app-gateway verifies; these scenarios replay or tamper, and check the
// mw-auth tokens (login, refresh rotation and reuse, the JWK Set, the edge).
export default [
  {
    name: '[device p2p] a P2P with a valid signature passes',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE_INCOME']);
      await S.p2p('customer01', '01711000002', 1000, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.changedBy({ '01711000001': -1500, '01711000002': 1000, 'FEE_INCOME': 435 });
    },
  },
  {
    name: '[device p2p] a replayed signed request is rejected and moves no money again',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE_INCOME']);
      await S.p2p('customer01', '01711000002', 1000, MW.pin);
      S.record(await MW.app.send(S.lastSubmit));
      S.status(401);
      S.code('MW-AUTH-006');
      await S.changedBy({ '01711000001': -1500, '01711000002': 1000, 'FEE_INCOME': 435 });
    },
  },
  {
    name: '[device p2p] a body changed after signing is rejected',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE_INCOME']);
      const signed = await MW.app.prepare('customer01', 'api-' + MW.uuid(), await MW.app.request('P2P', '01711000002', 1000, MW.pin));
      const tampered = signed.body.replace('"valueMinor":1000', '"valueMinor":1001');
      S.check('the body was changed', tampered !== signed.body);
      S.record(await MW.app.send({ body: tampered, headers: signed.headers }));
      S.status(401);
      S.code('MW-AUTH-004');
      await S.noChange();
    },
  },
  {
    name: '[device auth] a login on the bound device gets mw-auth tokens and the same signed login again is a replay',
    run: async (MW, S) => {
      const r = await MW.app.loginTwice('customer01');
      S.record(r.first);
      S.status(200);
      const c = MW.claims(r.first.body.accessToken);
      S.check('the token is mw-auth\'s: iss, aud, role, amr, device', c.iss === 'mw-auth' && c.aud === 'mw-app'
        && c.role === 'customer' && Array.isArray(c.amr) && c.amr.includes('pin') && !!c.deviceId && !!c.sid);
      S.check('a refresh token and the session idle timeout', !!r.first.body.refreshToken && r.first.body.idleTimeoutSeconds === 300);
      S.record(r.again);
      S.status(401);
      S.code('MW-AUTH-006');
    },
  },
  {
    name: '[device auth] a wrong PIN at login is refused',
    run: async (MW, S) => {
      S.record(await MW.app.loginWithPin('customer02', '86420'));
      S.status(401);
      S.code('MW-AUTH-040');
    },
  },
  {
    name: '[device auth] a refresh rotates the token and the used token again revokes the session',
    run: async (MW, S) => {
      const r = await MW.app.refreshTwice('merchant01');
      S.record(r.first);
      S.status(200);
      S.check('a new refresh token', !!r.first.body.refreshToken);
      S.record(r.again);
      S.status(401);
      S.code('MW-AUTH-048');
    },
  },
  {
    name: '[device auth edge] the app API refuses no token and serves an mw-auth token; the JWK Set is public',
    run: async (MW, S) => {
      S.record(await MW.http('GET', '/app/v1/billers'));
      S.status(401);
      S.code('MW-AUTH-001');
      S.record(await MW.http('GET', '/app/v1/billers', { headers: await MW.app.headers('customer01') }));
      S.status(200);
      const jwks = await MW.http('GET', '/auth/v1/.well-known/jwks.json');
      S.record(jwks);
      S.status(200);
      S.check('RSA RS256 keys, no private member', jwks.body.keys.length > 0
        && jwks.body.keys.every(k => k.kty === 'RSA' && k.alg === 'RS256' && !('d' in k)));
    },
  },
];

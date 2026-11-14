// Ticket 76: mw-auth's OTP, device binding, first PIN, login, refresh and logout (tickets 68-69,
// spec 18.7, D21), black-box through the edge route auth-v1. Each scenario takes a fresh wallet
// (MW.auth.fresh: one of core-mock's customers without an app PIN) so no shared app user is rebound
// or logged out. Folder 02 covers the signed-request replay and tamper rules and the refresh reuse.
export default [
  {
    name: '[auth] an OTP for an unknown MSISDN is refused; a known wallet gets a masked answer and the SMS',
    run: async (MW, S) => {
      const d = await MW.auth.device();
      S.record(await MW.auth.otp(d, '01799999999', 'BIND'));
      S.status(403);
      S.code('MW-AUTH-002');
      const w = await MW.auth.fresh(S, null);
      const again = await MW.auth.ask(w.d, w.msisdn, 'BIND');
      S.record(again.r);
      S.status(202);
      S.check('the answer names the wallet masked only', again.r.body.wallet === w.msisdn.slice(0, 4) + '****' + w.msisdn.slice(8)
        && !JSON.stringify(again.r.body).includes(w.msisdn), again.r.body);
      S.check('it says how many more OTPs the wallet may ask for', Number.isInteger(again.r.body.sendsLeft), again.r.body);
      S.check('the code reached the wallet by SMS', /^[0-9]{6}$/.test(again.code || ''));
    },
  },
  {
    name: '[auth] a wallet without a PIN logs in with a LOGIN OTP, sets its first PIN and gets a PIN session',
    run: async (MW, S) => {
      const w = await MW.auth.fresh(S, null);
      const otpClaims = MW.claims(w.otpLogin.accessToken);
      S.check('the OTP session: amr otp + device, no pin, pinSet false', otpClaims.amr.includes('otp') && otpClaims.amr.includes('device')
        && !otpClaims.amr.includes('pin') && w.otpLogin.pinSet === false, [otpClaims.amr, w.otpLogin.pinSet]);
      // an OTP session is no PIN session: the app API refuses it (no pin in amr)
      const refused = await MW.poll(() => MW.auth.billers(w.otpLogin.accessToken, w.d), r => r.status !== 200, 5000);
      S.record(refused);
      S.status(403);
      S.code('MW-AUTH-045');
      const p = S.record(await MW.auth.setPin(w.otpLogin.accessToken, '48207'));
      S.status(200);
      const c = MW.claims(p.body.accessToken);
      S.check('the new tokens carry amr pin + device and pinSet', c.amr.includes('pin') && c.amr.includes('device') && p.body.pinSet === true, [c.amr, p.body.pinSet]);
      S.record(await MW.auth.billers(p.body.accessToken, w.d));
      S.status(200);
      // the wallet has a PIN now: no more LOGIN OTP; it logs in with the PIN
      S.record(await MW.auth.otp(w.d, w.msisdn, 'LOGIN'));
      S.status(403);
      S.code('MW-AUTH-002');
      S.record(await MW.auth.login(w.d, w.msisdn, { pin: '48207' }));
      S.status(200);
    },
  },
  {
    name: '[auth device] a wrong binding OTP is refused, the right one binds the device and a new-device SMS follows',
    run: async (MW, S) => {
      const w = await MW.auth.fresh(S, '48207');
      const d2 = await MW.auth.device();
      const o = await MW.auth.ask(d2, w.msisdn, 'BIND');
      S.record(o.r);
      S.status(202);
      S.record(await MW.auth.bind(d2, w.msisdn, o.code === '000000' ? '999999' : '000000', '48207'));
      S.status(401);
      S.code('MW-AUTH-010');
      S.record(await MW.auth.bind(d2, w.msisdn, o.code, MW.WRONG_PIN));
      S.status(401);
      S.code('MW-AUTH-040');
      const notice = 'A new device was bound to your Bits wallet. If this was not you, call Bits support now.';
      const before = (await MW.sim.sms(w.msisdn)).filter(m => m.text === notice).length;
      const o2 = await MW.auth.ask(d2, w.msisdn, 'BIND');
      const b = S.record(await MW.auth.bind(d2, w.msisdn, o2.code, '48207'));
      S.status(201);
      S.check('bound now, wallet masked', b.body.deviceId === d2.id && b.body.wallet.includes('****')
        && Date.parse(b.body.boundAt) > Date.parse(w.boundAt), b.body);
      const got = await MW.poll(async () => (await MW.sim.sms(w.msisdn)).filter(m => m.text === notice).length, n => n > before, 30000, 700);
      S.eq('one new-device SMS more', got, before + 1);
    },
  },
  {
    // spec 18.7: one bound device per wallet; a new binding ends the old device's sessions at once
    name: '[auth device] binding a second device unbinds the first: its login and refresh are refused, the new one logs in',
    run: async (MW, S) => {
      const w = await MW.auth.fresh(S, '48207');
      const old = w.tokens;
      const d2 = await MW.auth.device();
      const o = await MW.auth.ask(d2, w.msisdn, 'BIND');
      S.record(await MW.auth.bind(d2, w.msisdn, o.code, '48207'));
      S.status(201);
      S.record(await MW.auth.login(w.d, w.msisdn, { pin: '48207' }));
      S.status(401);
      S.code('MW-AUTH-047');
      const r = S.record(await MW.auth.refresh(w.d, old.refreshToken));
      S.check('the first device\'s refresh token is dead', r.status === 401 && ['MW-AUTH-047', 'MW-AUTH-048'].includes(r.body && r.body.code), String(r));
      // the gateway caches a live session for 10 s at most
      const gone = await MW.poll(() => MW.auth.billers(old.accessToken, w.d), x => x.status === 401, 20000, 1000);
      S.eq('the first device\'s access token no longer reaches the app API', gone.status, 401, String(gone));
      const l = S.record(await MW.auth.login(d2, w.msisdn, { pin: '48207' }));
      S.status(200);
      S.eq('the new device\'s token names it', MW.claims(l.body.accessToken).deviceId, d2.id);
    },
  },
  {
    name: '[auth] logout ends the session: the refresh token is revoked and the app API refuses the token',
    run: async (MW, S) => {
      const w = await MW.auth.fresh(S, '48207');
      const t = w.tokens;
      S.record(await MW.auth.billers(t.accessToken, w.d));
      S.status(200);
      S.record(await MW.auth.logout(t.accessToken));
      S.status(204);
      S.record(await MW.auth.refresh(w.d, t.refreshToken));
      S.status(401);
      S.code('MW-AUTH-048');
      const gone = await MW.poll(() => MW.auth.billers(t.accessToken, w.d), x => x.status === 401, 20000, 1000);
      S.record(gone);
      S.status(401);
      S.code('MW-AUTH-007');
      S.record(await MW.auth.logout('not-a-token'));
      S.status(401);
    },
  },
  {
    name: '[auth edge] the key documents are public: access-token JWKS RS256, assertion JWKS ES256, the PIN transport key',
    run: async (MW, S) => {
      const at = S.record(await MW.http('GET', '/auth/v1/.well-known/jwks.json'));
      S.status(200);
      S.check('RSA RS256 only, public members only', at.body.keys.length > 0 && at.body.keys.every(k => k.kty === 'RSA' && k.alg === 'RS256' && !('d' in k)), at.body);
      const as = S.record(await MW.http('GET', '/auth/v1/.well-known/assertion-jwks.json'));
      S.status(200);
      S.check('EC P-256 ES256 only, public members only', as.body.keys.length > 0
        && as.body.keys.every(k => k.kty === 'EC' && k.crv === 'P-256' && k.alg === 'ES256' && !('d' in k)), as.body);
      S.check('the two sets share no key', !as.body.keys.some(k => at.body.keys.some(a => a.kid === k.kid)));
      const pk = S.record(await MW.http('GET', '/auth/v1/pin-key'));
      S.status(200);
      S.check('an RSA-OAEP-256 PEM public key with its keyId', /-----BEGIN PUBLIC KEY-----/.test(pk.body.publicKeyPem) && !!pk.body.keyId, pk.body);
      // the discovery document names the in-stack JWKS URL: only APISIX reads it, the edge does not route it
      const disc = await MW.http('GET', '/auth/v1/.well-known/openid-configuration');
      S.eq('the discovery document is not routed', disc.status, 404, String(disc));
    },
  },
];

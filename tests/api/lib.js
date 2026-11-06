// Shared script library of the Bits MFS middleware Newman suite (ticket 58). build_collection.mjs
// embeds this file as the collection variable "lib"; every script starts with
// `const MW = eval(pm.collectionVariables.get('lib'));`. Edit this file and regenerate
// (node tests/api/build_collection.mjs); never edit the copy inside the collection JSON.
//
// It brings over the Cucumber suite's support/ helpers (tests/e2e, deleted in ticket 58): the
// APISIX edge client, logins per principal with a token cache, the app's bound device and request
// signature, RSA-OAEP PIN encryption, partner/checkout HMACs, the simulators' control API, poll().
// Every call goes through APISIX; no request needs Docker.
(function () {
  const CV = pm.collectionVariables;
  const v = k => pm.variables.get(k);
  const CJS = require('crypto-js');
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  const short = o => { const s = typeof o === 'string' ? o : JSON.stringify(o); return s && s.length > 700 ? s.slice(0, 700) + '…' : s; };
  const uuid = () => pm.variables.replaceIn('{{$guid}}');
  const FINAL = ['COMPLETED', 'FAILED', 'REJECTED', 'REVERSED'];

  // ---------- HTTP through the edge ----------
  function sendRaw(req) {
    return new Promise((resolve, reject) => pm.sendRequest(req, (err, res) => (err ? reject(err) : resolve(res))));
  }
  /**
   * opts: headers {}, json (object, sent as JSON), raw (exact text), form {} (urlencoded),
   * contentType, apikey (true: the test-only key-auth header). Returns {status, body, raw, header(name)}.
   */
  async function http(method, path, opts) {
    opts = opts || {};
    const header = [];
    const h = Object.assign({ 'X-Correlation-Id': uuid() }, opts.headers || {});
    if (opts.apikey) h.apikey = v('e2eApiKey');
    let body;
    if (opts.form) {
      h['Content-Type'] = 'application/x-www-form-urlencoded';
      body = { mode: 'urlencoded', urlencoded: Object.keys(opts.form).map(k => ({ key: k, value: String(opts.form[k]) })) };
    } else if (opts.json !== undefined) {
      h['Content-Type'] = 'application/json';
      body = { mode: 'raw', raw: JSON.stringify(opts.json) };
    } else if (opts.raw !== undefined) {
      h['Content-Type'] = opts.contentType || 'application/json';
      body = { mode: 'raw', raw: opts.raw };
    }
    Object.keys(h).forEach(k => header.push({ key: k, value: h[k] }));
    const req = { url: (path.startsWith('http') ? path : v('baseUrl') + path), method, header };
    if (body) req.body = body;
    let res;
    for (let attempt = 0; ; attempt++) {
      try { res = await sendRaw(req); break; } catch (e) {
        if (attempt >= 2) throw new Error(method + ' ' + path + ' failed: ' + e.message);
        await sleep(500);
      }
    }
    const raw = res.text();
    let parsed = null;
    try { parsed = raw ? JSON.parse(raw) : null; } catch (e) { parsed = null; }
    return { status: res.code, body: parsed, raw, header: n => res.headers.get(n), toString: () => 'HTTP ' + res.code + ' ' + short(raw) };
  }
  async function must(method, path, opts, ok) {
    const r = await http(method, path, opts);
    if (!(ok || [200]).includes(r.status)) throw new Error(method + ' ' + path + ' -> ' + r);
    return r;
  }

  // ---------- Polling ----------
  /** Calls fn until until(value) holds or timeoutMs passes; returns the last value either way. */
  async function poll(fn, until, timeoutMs, delayMs) {
    const deadline = Date.now() + (timeoutMs || 20000);
    let last = await fn();
    while (!until(last) && Date.now() < deadline) {
      await sleep(delayMs || 500);
      last = await fn();
    }
    return last;
  }

  // ---------- Encoding helpers ----------
  const hexToB64 = hex => CJS.enc.Base64.stringify(CJS.enc.Hex.parse(hex));
  const sha256Hex = s => CJS.SHA256(CJS.enc.Utf8.parse(s)).toString(CJS.enc.Hex);
  const hmacHex = (secret, s) => CJS.HmacSHA256(CJS.enc.Utf8.parse(s), CJS.enc.Utf8.parse(secret)).toString(CJS.enc.Hex);
  const hmacB64 = (secret, s) => CJS.HmacSHA256(CJS.enc.Utf8.parse(s), CJS.enc.Utf8.parse(secret)).toString(CJS.enc.Base64);
  function b64urlJson(part) {
    let s = part.replace(/-/g, '+').replace(/_/g, '/');
    while (s.length % 4) s += '=';
    return JSON.parse(CJS.enc.Utf8.stringify(CJS.enc.Base64.parse(s)));
  }
  const claims = t => b64urlJson(t.split('.')[1]);

  /** RFC 6238 TOTP as realm ops is set up: HmacSHA256 keyed with the secret's UTF-8 bytes, 6 digits, 30 s. */
  function totp(secret, atMs) {
    const step = Math.floor((atMs || Date.now()) / 1000 / 30);
    const hi = Math.floor(step / 0x100000000), lo = step >>> 0;
    const msg = CJS.lib.WordArray.create([hi, lo], 8);
    const h = CJS.HmacSHA256(msg, CJS.enc.Utf8.parse(secret)).toString(CJS.enc.Hex);
    const o = parseInt(h.slice(-1), 16);
    const bin = parseInt(h.substr(o * 2, 8), 16) & 0x7fffffff;
    return String(bin % 1000000).padStart(6, '0');
  }

  // ---------- Pinned third-party code (RSA-OAEP, ECDSA P-256): only the exact file is evaluated ----------
  const libs = {};
  async function pinned(name, urlVar, shaVar, globalName) {
    if (libs[name]) return libs[name];
    let src = CV.get('_src_' + name);
    if (!src) {
      const res = await sendRaw(v(urlVar));
      if (res.code !== 200) throw new Error('could not load ' + name + ' from ' + v(urlVar) + ' (HTTP ' + res.code + ')');
      src = res.text();
      const digest = CJS.SHA256(src).toString();
      if (digest !== v(shaVar)) throw new Error(name + ' integrity check failed: SHA-256 ' + digest);
      CV.set('_src_' + name, src);
    }
    const m = { exports: {} };
    const rnd = { getRandomValues: a => { for (let i = 0; i < a.length; i++) a[i] = Math.floor(Math.random() * 256); return a; } };
    const scope = { crypto: rnd };
    libs[name] = new Function('module', 'exports', 'window', 'self', 'define', src + '\n;return module.exports;')(m, m.exports, scope, scope, undefined)
      || scope[globalName];
    return libs[name];
  }
  const forge = () => pinned('forge', 'forgeUrl', 'forgeSha256', 'forge');
  const elliptic = () => pinned('elliptic', 'ellipticUrl', 'ellipticSha256', 'elliptic');

  // ---------- core-mock test API (/test/core-mock/v1/*) ----------
  async function pinKey() {
    let k = CV.get('_pinKey');
    if (!k) {
      const r = await must('GET', '/test/core-mock/v1/pin-public-key', { apikey: true });
      k = JSON.stringify({ keyId: r.body.keyId, pem: r.body.publicKeyPem });
      CV.set('_pinKey', k);
    }
    return JSON.parse(k);
  }
  /** The PIN as an app encrypts it: RSA-OAEP, SHA-256 (hash and MGF1), core key; {keyId, ciphertext}. */
  async function encryptPin(pin) {
    const f = await forge();
    const k = await pinKey();
    const pub = f.pki.publicKeyFromPem(k.pem);
    const ct = pub.encrypt(f.util.encodeUtf8(String(pin)), 'RSA-OAEP', { md: f.md.sha256.create(), mgf1: { md: f.md.sha256.create() } });
    return { keyId: k.keyId, ciphertext: f.util.encode64(ct) };
  }
  const core = {
    async wallet(id) { return (await must('GET', '/test/core-mock/v1/wallets/' + id, { apikey: true })).body; },
    async fault(f) { await must('POST', '/test/core-mock/v1/faults', { apikey: true, json: f }, [201]); },
    async resetFaults() { await must('DELETE', '/test/core-mock/v1/faults', { apikey: true }, [204]); },
  };

  // ---------- simulators (/test/sim/*), mail (/test/mail/*) ----------
  const sim = {
    async scenario(partner, s) { await must('POST', '/test/sim/' + partner + '/scenario', { apikey: true, json: s }, [200, 201, 202, 204]); },
    async reset(partner) { await must('DELETE', '/test/sim/' + partner + '/scenario', { apikey: true }, [204]); },
    async requests(partner) { return (await must('GET', '/test/sim/' + partner + '/requests', { apikey: true })).body || []; },
    async lastSeq(partner) { return (await sim.requests(partner)).reduce((m, r) => Math.max(m, r.seq || 0), 0); },
    async replay(partner, ref) {
      return (await must('POST', '/test/sim/' + partner + '/callbacks/' + ref + '/replay', { apikey: true, json: {} })).body.targetStatus;
    },
    async sms(msisdn) { return (await must('GET', '/test/sim/smsc/messages?msisdn=' + msisdn, { apikey: true })).body || []; },
    async mail(email) {
      const r = await http('GET', '/test/mail/user/' + encodeURIComponent(email) + '/messages/', { apikey: true });
      if (r.status === 400) return [];
      if (r.status !== 200) throw new Error('mail server query failed: ' + r);
      return r.body || [];
    },
    async ussd(mno, msisdn, code, inputs, sessionId) {
      const b = { mno, msisdn, serviceCode: code, inputs };
      if (sessionId) { b.sessionId = sessionId; b.resume = true; }
      const r = await must('POST', '/test/sim/ussdgw/sessions', { apikey: true, json: b });
      return r.body;
    },
    async smppBinds() { return (await must('GET', '/test/sim/ussdgw/smpp/binds', { apikey: true })).body; },
    async smppScenario(s) { await must('POST', '/test/sim/ussdgw/smpp/scenario', { apikey: true, json: s }, [204]); },
    async smppDrop() { await must('POST', '/test/sim/ussdgw/smpp/drop', { apikey: true, json: {} }, [204]); },
    async ipnFail(ipnId, n, status) { await must('POST', '/test/sim/merchant/ipn/scenario', { apikey: true, json: { failNext: n, failStatus: status, ipnId } }); },
    async ipnAcceptAll() { await http('POST', '/test/sim/merchant/ipn/scenario', { apikey: true, json: { failNext: 0 } }); },
    async ipnReceived(ipnId) { return (await must('GET', '/test/sim/merchant/ipn/received?ipnId=' + ipnId, { apikey: true })).body || []; },
  };

  // ---------- Tokens (Keycloak through APISIX), cached per user, refreshed with the refresh token ----------
  const REALMS = {
    customers: { client: 'mw-app', password: 'kcCustomersPassword' },
    portals: { client: 'mw-portal', password: 'kcPortalsPassword' },
    ops: { client: 'mw-e2e-ops', password: 'kcOpsPassword', totp: true },
  };
  async function token(realm, user) {
    user = loginUser(realm, user);
    const key = 'tok:' + realm + '/' + user;
    const raw = CV.get(key);
    const now = Date.now();
    const cfg = REALMS[realm];
    const path = '/auth/realms/' + realm + '/protocol/openid-connect/token';
    if (raw) {
      const t = JSON.parse(raw);
      if (t.exp - 30000 > now) return t.access;
      if (t.refresh && t.rexp - 30000 > now) {
        const r = await http('POST', path, { apikey: !!cfg.totp, form: { client_id: cfg.client, grant_type: 'refresh_token', refresh_token: t.refresh } });
        if (r.status === 200) return store(key, r.body);
      }
    }
    const form = { client_id: cfg.client, grant_type: 'password', username: user, password: v(cfg.password) };
    let r;
    for (let i = 0; i < 3; i++) {
      if (cfg.totp) {
        // Keycloak refuses a code this user already used: wait for the next 30 s step if needed
        const step = Math.floor(Date.now() / 30000);
        if (CV.get('totpStep:' + user) === String(step)) await sleep(30000 - (Date.now() % 30000) + 200);
        form.totp = totp(v('kcOpsTotpSecret'));
        CV.set('totpStep:' + user, String(Math.floor(Date.now() / 30000)));
      }
      r = await http('POST', path, { apikey: !!cfg.totp, form });
      if (r.status === 200) return store(key, r.body);
      if (r.status !== 401 && r.status !== 429 && r.status !== 400) break;
      await sleep(r.status === 429 ? 7000 : 1000);
    }
    throw new Error('no token for ' + realm + '/' + user + ': ' + r);
  }
  function store(key, b) {
    const now = Date.now();
    CV.set(key, JSON.stringify({ access: b.access_token, refresh: b.refresh_token, exp: now + b.expires_in * 1000, rexp: now + (b.refresh_expires_in || 0) * 1000 }));
    return b.access_token;
  }
  const bearer = async (realm, user) => ({ Authorization: 'Bearer ' + (await token(realm, user)) });

  // ---------- The app's bound device (EC P-256) and request signatures ----------
  const SPKI_P256 = '3059301306072a8648ce3d020106082a8648ce3d030107034200';
  async function device(user) {
    const raw = CV.get('dev:' + user);
    if (raw) return JSON.parse(raw);
    const E = await elliptic();
    const ec = new E.ec('p256');
    const entropy = sha256Hex(uuid() + Math.random() + Date.now()) + sha256Hex(uuid() + Math.random());
    const kp = ec.genKeyPair({ entropy, entropyEnc: 'hex' });
    const d = { id: 'api-' + uuid(), priv: kp.getPrivate('hex'), pub: hexToB64(SPKI_P256 + kp.getPublic(false, 'hex')), bound: false, lastUse: 0 };
    CV.set('dev:' + user, JSON.stringify(d));
    return d;
  }
  const saveDevice = (user, d) => CV.set('dev:' + user, JSON.stringify(d));
  async function sign(d, canonical) {
    const E = await elliptic();
    const ec = new E.ec('p256');
    const key = ec.keyFromPrivate(d.priv, 'hex');
    const der = key.sign(sha256Hex(canonical)).toDER('hex');
    return hexToB64(der);
  }
  async function signedHeaders(user, d, method, path, body) {
    const ts = String(Date.now());
    const nonce = 'api-' + uuid();
    const canonical = [method, path, sha256Hex(body), ts, nonce, d.id].join('\n');
    return Object.assign(await bearer('customers', user), {
      'X-Device-Id': d.id, 'X-Timestamp': ts, 'X-Nonce': nonce, 'X-Signature': await sign(d, canonical),
    });
  }
  const BIND_OTP = /Your Bits code to bind a new device is ([0-9]{6})/;
  async function ready(user) {
    const d = await device(user);
    if (!d.bound) {
      const wallet = claims(await token('customers', user)).msisdn;
      const otps = async () => (await sim.sms(wallet)).map(m => BIND_OTP.exec(m.text || '')).filter(Boolean).map(m => m[1]);
      const before = await otps();
      const h = Object.assign(await bearer('customers', user), { 'X-Device-Id': d.id });
      const r = await http('POST', '/app/v1/devices/otp', { headers: h, raw: '{"locale":"en"}' });
      if (r.status !== 202) throw new Error('asking for the binding OTP of ' + user + ' failed: ' + r);
      const fresh = await poll(async () => (await otps()).filter(c => !before.includes(c)), l => l.length > 0, 30000);
      if (!fresh.length) throw new Error('no binding OTP SMS reached the wallet of ' + user);
      const body = JSON.stringify({ publicKey: d.pub, otp: fresh[fresh.length - 1] });
      const b = await http('POST', '/app/v1/devices', { headers: await signedHeaders(user, d, 'POST', '/app/v1/devices', body), raw: body });
      if (b.status !== 201) throw new Error('binding the device of ' + user + ' failed: ' + b);
      d.bound = true;
    } else if (Date.now() - d.lastUse > 4 * 60000) {
      const r = await http('POST', '/app/v1/sessions', { headers: await signedHeaders(user, d, 'POST', '/app/v1/sessions', ''), raw: '' });
      if (r.status !== 200) throw new Error('opening a session for ' + user + ' failed: ' + r);
    }
    d.lastUse = Date.now();
    saveDevice(user, d);
    return d;
  }

  // ---------- App API (/app/v1/*) ----------
  const app = {
    async headers(user) { const d = await ready(user); return Object.assign(await bearer('customers', user), { 'X-Device-Id': d.id }); },
    /** A transaction body: txnType, credit party, amount (poisha) and the encrypted PIN. */
    async request(txnType, credit, amount, pin) {
      return { txnType, creditParty: { id: credit }, amount: { valueMinor: amount, currency: 'BDT' }, encryptedPin: await encryptPin(pin) };
    },
    /** The signed submit exactly as sent: {body, headers}, so it can be replayed byte for byte. */
    async prepare(user, idem, body) {
      const d = await ready(user);
      const json = typeof body === 'string' ? body : JSON.stringify(body);
      const h = await signedHeaders(user, d, 'POST', '/app/v1/transactions', json);
      h['Idempotency-Key'] = idem;
      return { body: json, headers: h };
    },
    send(p) { return http('POST', '/app/v1/transactions', { headers: p.headers, raw: p.body }); },
    async status(user, txnId) { return http('GET', '/app/v1/transactions/' + txnId, { headers: await app.headers(user) }); },
    async billers(user) { return http('GET', '/app/v1/billers', { headers: await app.headers(user) }); },
    async fetchBill(user, biller, bill) { return http('POST', '/app/v1/billers/' + biller + '/fetch-bill', { headers: await app.headers(user), json: { billNumber: bill } }); },
    async collections(user) { return http('GET', '/app/v1/merchant/collections', { headers: await app.headers(user) }); },
  };

  // ---------- Admin API (/admin/v1/*, realm ops) ----------
  const ops = async user => bearer('ops', user);
  const admin = {
    async txn(user, id) { return http('GET', '/admin/v1/transactions/' + id, { headers: await ops(user) }); },
    async search(user, filter, value, type) {
      return http('GET', '/admin/v1/transactions?' + filter + '=' + encodeURIComponent(value) + (type ? '&txnType=' + type : '') + '&limit=50', { headers: await ops(user) });
    },
    async ask(user, txnId, action, reason) { return http('POST', '/admin/v1/transactions/' + txnId + '/change-requests', { headers: await ops(user), json: { action, reason } }); },
    async askWallet(user, w, action, reason) { return http('POST', '/admin/v1/wallets/' + w + '/change-requests', { headers: await ops(user), json: { action, reason } }); },
    async askPartner(user, p, body) { return http('POST', '/admin/v1/partners/' + p + '/change-requests', { headers: await ops(user), json: body }); },
    async partner(user, p) { return http('GET', '/admin/v1/partners/' + p, { headers: await ops(user) }); },
    async proposeMenu(user, menuId, body) { return http('POST', '/admin/v1/ussd-menus/' + menuId + '/change-requests', { headers: await ops(user), json: body }); },
    async proposeTemplate(user, code, ch, loc, body) { return http('POST', '/admin/v1/notify-templates/' + code + '/' + ch + '/' + loc + '/change-requests', { headers: await ops(user), json: body }); },
    async cr(user, id) { return http('GET', '/admin/v1/change-requests/' + id, { headers: await ops(user) }); },
    async approve(user, id) { return http('POST', '/admin/v1/change-requests/' + id + '/approve', { headers: await ops(user), raw: '' }); },
    async reject(user, id, reason) { return http('POST', '/admin/v1/change-requests/' + id + '/reject', { headers: await ops(user), json: { reason } }); },
  };

  // ---------- Org portal (/portal/org/v1/*, realm portals) ----------
  const portals = async user => bearer('portals', user);
  const B = '/portal/org/v1/bulk/batches';
  const portal = {
    async upload(user, csv) { return http('POST', B, { headers: await portals(user), raw: csv, contentType: 'text/csv' }); },
    async get(user, id) { return http('GET', B + '/' + id, { headers: await portals(user) }); },
    async list(user, status) { return http('GET', B + (status ? '?status=' + status : ''), { headers: await portals(user) }); },
    async approve(user, id) { return http('POST', B + '/' + id + '/approve', { headers: await portals(user), raw: '' }); },
    async reject(user, id, reason) { return http('POST', B + '/' + id + '/reject', { headers: await portals(user), json: { reason } }); },
    async pause(user, id) { return http('POST', B + '/' + id + '/pause', { headers: await portals(user), raw: '' }); },
    async resume(user, id) { return http('POST', B + '/' + id + '/resume', { headers: await portals(user), raw: '' }); },
    async result(user, id) { return http('GET', B + '/' + id + '/result', { headers: await portals(user) }); },
    async txns(user, batchId) { return http('GET', '/portal/org/v1/transactions' + (batchId ? '?batchId=' + batchId : ''), { headers: await portals(user) }); },
    async txn(user, id) { return http('GET', '/portal/org/v1/transactions/' + id, { headers: await portals(user) }); },
    /** rows payees of 1.00 taka each (01710000001..), fresh references. */
    file(rows) {
      const run = unique();
      let s = 'msisdn,amount,reference\n';
      for (let i = 1; i <= rows; i++) s += '0171' + String(i).padStart(7, '0') + ',1.00,API-' + run + '-' + i + '\n';
      return s;
    },
    /** rows payouts of taka each to the core mock's seeded customers 01711000001..01711001000 in turn. */
    payoutFile(rows, taka) {
      const run = unique();
      let s = 'msisdn,amount,reference\n';
      for (let i = 1; i <= rows; i++) s += '0171100' + String((i - 1) % 1000 + 1).padStart(4, '0') + ',' + taka + ',PAY-' + run + '-' + i + '\n';
      return s;
    },
    /** Waits until no batch of the user's org is still sending rows (APPROVED or EXECUTING). */
    async idle(user, timeoutMs) {
      return poll(() => portal.list(user), r => r.status === 200 && !((r.body || {}).batches || []).some(b => ['APPROVED', 'EXECUTING'].includes(b.status)), timeoutMs || 900000, 2000);
    },
  };

  // ---------- Web checkout (/checkout/v1/*, APISIX hmac-auth as merchant_demo) ----------
  const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  function httpDate(d) {
    const p = n => String(n).padStart(2, '0');
    return DAYS[d.getUTCDay()] + ', ' + p(d.getUTCDate()) + ' ' + MONTHS[d.getUTCMonth()] + ' ' + d.getUTCFullYear() + ' '
      + p(d.getUTCHours()) + ':' + p(d.getUTCMinutes()) + ':' + p(d.getUTCSeconds()) + ' GMT';
  }
  const IPN_URL = 'http://mw-simulators:8080/merchant/ipn/e2e';
  const checkout = {
    signed(method, path, body, secret) {
      const date = httpDate(new Date());
      const h = { Date: date };
      let signing = 'merchant-demo\n' + method + ' ' + path + '\ndate: ' + date + '\n';
      let names = '@request-target date';
      if (body !== null && body !== undefined) {
        const digest = 'SHA-256=' + CJS.SHA256(CJS.enc.Utf8.parse(body)).toString(CJS.enc.Base64);
        h.Digest = digest;
        signing += 'digest: ' + digest + '\n';
        names += ' digest';
      }
      h.Authorization = 'Signature keyId="merchant-demo",algorithm="hmac-sha256",headers="' + names + '",signature="' + hmacB64(secret, signing) + '"';
      return h;
    },
    sessionBody(orderId, amount) {
      return JSON.stringify({ orderId, amount: { valueMinor: amount, currency: 'BDT' }, successUrl: 'https://shop.example.test/checkout/ok?cart=7',
        failUrl: 'https://shop.example.test/checkout/fail', cancelUrl: 'https://shop.example.test/checkout/cancel', ipnUrl: IPN_URL,
        description: 'api order ' + orderId, locale: 'en' });
    },
    create(body, secret) { return http('POST', '/checkout/v1/sessions', { headers: secret === null ? {} : checkout.signed('POST', '/checkout/v1/sessions', body, secret), raw: body }); },
    get(id) { const p = '/checkout/v1/sessions/' + id; return http('GET', p, { headers: checkout.signed('GET', p, null, v('checkoutSecret')) }); },
    page(t) { return http('GET', '/checkout/pay/v1/' + t); },
    otp(t, wallet) { return http('POST', '/checkout/pay/v1/' + t + '/otp', { json: { wallet } }); },
    async confirm(t, otp, pin) { return http('POST', '/checkout/pay/v1/' + t + '/confirm', { json: { otp, encryptedPin: await encryptPin(pin) } }); },
    back(t) { return http('GET', '/checkout/pay/v1/' + t + '/return'); },
    query(url) {
      const out = {};
      const q = url.indexOf('?') < 0 ? '' : url.slice(url.indexOf('?') + 1);
      q.split('&').filter(Boolean).forEach(p => { const i = p.indexOf('='); out[decodeURIComponent(p.slice(0, i).replace(/\+/g, ' '))] = decodeURIComponent(p.slice(i + 1).replace(/\+/g, ' ')); });
      return out;
    },
    /** The merchant's check of the signed result (contract checkout.v1). */
    verifyResult(q, secret) {
      const c = ['bits-checkout-result.v1', q.sessionId || '', q.orderId || '', q.status || '', q.txnId || '', q.amount || '', q.currency || '', q.errorCode || '', q.ts || ''].join('\n');
      return !!q.signature && hmacHex(secret, c) === q.signature;
    },
    /** The merchant's IPN check: hex HMAC over bits-checkout-ipn.v1\n<timestamp>\n<body>. */
    verifyIpn(ipn, secret) {
      const h = ipn.headers || {};
      return !!h['x-bits-signature'] && !!h['x-bits-timestamp']
        && hmacHex(secret, 'bits-checkout-ipn.v1\n' + h['x-bits-timestamp'] + '\n' + ipn.body) === h['x-bits-signature'];
    },
  };

  // ---------- Partner callbacks (billagg) ----------
  const callbacks = {
    reference: txnId => 'BITS' + txnId.replace(/-/g, '').toUpperCase(),
    body: (ref, status, code) => JSON.stringify({ transactionId: ref, status, code, message: 'api callback' }),
    send(body, secret) {
      const path = '/partner/billagg/callback/payments';
      const ts = String(Math.floor(Date.now() / 1000));
      const sig = hmacB64(secret, 'POST\n' + path + '\n' + ts + '\n' + sha256Hex(body));
      return http('POST', path, { headers: { 'X-Signature': sig, 'X-Timestamp': ts }, raw: body });
    },
    billaggCallbacks: (mode, delayMs) => sim.scenario('billagg', { latencyMs: 0, errorRate: 0.0, outage: false, callbackOnly: true, callbackMode: mode,
      callbackUrl: 'http://apisix:9080/partner/billagg/callback/payments', callbackDelayMs: delayMs || 0 }),
    bankCallbacks: (mode, delayMs) => sim.scenario('c2wbank', { latencyMs: 0, errorRate: 0.0, outage: false, callbackMode: mode,
      callbackUrl: 'http://apisix:9080/partner/c2wbank/callback/debits', callbackDelayMs: delayMs || 0 }),
  };

  // ---------- Test-only APIs of mw-orchestrator and mw-audit-dw (ticket 58) ----------
  const testApi = {
    reverse: (txnId, reason) => must('POST', '/test/orchestrator/v1/reversals', { apikey: true, json: { originalTxnId: txnId, reason: reason || 'api reversal' } }, [202]),
    handled: key => http('GET', '/test/orchestrator/v1/txn-requests/' + encodeURIComponent(key), { apikey: true }),
    poison: () => must('POST', '/test/orchestrator/v1/poison-requests', { apikey: true }, [202]),
    dlqRecord: txnId => http('GET', '/test/orchestrator/v1/dlq-records/' + encodeURIComponent(txnId), { apikey: true }),
    auditEvents: query => must('GET', '/test/audit-dw/v1/audit/events?' + query, { apikey: true }),
    chain: month => must('GET', '/test/audit-dw/v1/audit/chains/' + month, { apikey: true }),
  };

  /** The seeded time limit of partner ocs (manifest, passed by run.sh): 20 s, or 5 s with TIMERS=fast. */
  const ocsLimit = () => Number(v('ocsTimeoutMs')) || 20000;

  // ---------- Run customer (run.sh picks it with run_customer.mjs) ----------
  // The scenarios pay mostly from customer01 (01711000001); one run spends about 4,000 BDT of the
  // 15,000 BDT a core-mock wallet may debit per UTC day. So every run plays customer01 as the run
  // customer run.sh passed (mw-seeder's seed-run-customerNN, MSISDN 01714NN0001, which masks as
  // 0171****001 too): its scenario source has customer01's number swapped in every form (0..., 880...)
  // before it runs, and the customers-realm login of customer01 becomes the run customer's. Without
  // runCustomerMsisdn (a manual run) nothing changes.
  const CUSTOMER01_CORE = '1711000001';
  function runCustomer() {
    const msisdn = v('runCustomerMsisdn');
    return msisdn && /^01[3-9]\d{8}$/.test(msisdn) ? { user: v('runCustomerUser'), msisdn } : null;
  }
  function forRun(src) {
    const c = runCustomer();
    return c ? src.split(CUSTOMER01_CORE).join(c.msisdn.slice(1)) : src;
  }
  function loginUser(realm, user) {
    const c = runCustomer();
    return c && c.user && realm === 'customers' && user === 'customer01' ? c.user : user;
  }

  // ---------- Unique data ----------
  let counter = 0;
  function unique() { counter++; return Date.now().toString(36) + counter.toString(36) + Math.floor(Math.random() * 1296).toString(36); }
  const token4 = () => String(1000 + Math.floor(Math.random() * 9000));

  // ---------- Scenario runner ----------
  class Stop extends Error {}
  /** One scenario's state and assertions (the Cucumber ScenarioState plus the step assertions). */
  function Scenario(name) {
    const S = { name, responses: [], noted: {}, afterFns: [], token: token4(), user: null };
    S.check = (label, cond, detail) => {
      pm.test(label, () => pm.expect(cond, detail === undefined ? undefined : short(detail)).to.be.true);
      if (!cond) throw new Stop(label);
    };
    S.eq = (label, actual, expected, detail) => {
      const ok = JSON.stringify(actual) === JSON.stringify(expected);
      pm.test(label, () => pm.expect(actual, short(detail === undefined ? actual : detail)).to.eql(expected));
      if (!ok) throw new Stop(label + ': got ' + short(actual));
    };
    S.after = fn => S.afterFns.push(fn);
    S.record = r => { S.responses.push(r); return r; };
    S.last = () => S.responses[S.responses.length - 1];
    S.txnId = () => {
      const r = S.responses.find(x => x.body && x.body.txnId);
      if (!r) throw new Error('no response carried a txnId: ' + short(S.responses.map(String)));
      return r.body.txnId;
    };
    S.status = (code, r) => { r = r || S.last(); S.eq('HTTP status ' + code, r.status, code, String(r)); };
    S.code = (code, r) => { r = r || S.last(); S.eq('error code ' + code, r.body && r.body.code, code, String(r)); };
    S.txnStatus = st => S.eq('transaction status ' + st, S.last().body && S.last().body.status, st, String(S.last()));
    S.carriesTxnId = () => S.check('the response carries a txnId', !!(S.last().body && S.last().body.txnId), String(S.last()));
    S.sameTxnId = () => {
      const id = S.txnId();
      S.check('every response carries the same txnId', S.responses.every(r => r.body && r.body.txnId === id), S.responses.map(r => r.body && r.body.txnId));
    };

    // money: balances noted before, exact deltas after
    S.note = async wallets => { for (const w of wallets) S.noted[w] = await core.wallet(w); };
    S.deltas = async () => {
      const out = {};
      for (const w of Object.keys(S.noted)) out[w] = (await core.wallet(w)).balanceMinor - S.noted[w].balanceMinor;
      return out;
    };
    S.changedBy = async expected => {
      // the core applies asynchronously to the answer in a few paths: give it a moment to settle
      const want = JSON.stringify(expected);
      const got = await poll(() => S.deltas(), d => JSON.stringify(d) === want, 5000);
      S.eq('core balances changed by ' + want, got, expected);
    };
    S.noChange = async () => {
      const none = {};
      Object.keys(S.noted).forEach(w => { none[w] = 0; });
      S.eq('no core balance changed', await S.deltas(), none);
    };
    S.noHold = async w => S.eq('no funds held on ' + w, (await core.wallet(w)).heldMinor, S.noted[w].heldMinor);

    // core faults and simulator scenarios, always reset afterwards
    S.fault = async (mode, cmd, extra) => {
      if (!S.faulted) { S.faulted = true; S.after(() => core.resetFaults()); }
      await core.fault(Object.assign({ mode, commandType: cmd, times: 1 }, extra || {}));
    };
    S.dropReply = cmd => S.fault('DROP_REPLY', cmd);
    S.reject = (cmd, reason) => S.fault('REJECT', cmd, reason ? { reasonCode: reason } : {});
    S.refusalLost = async cmd => { await S.fault('REJECT', cmd); await S.fault('DROP_REPLY', cmd); };
    S.force = async (partner, scenario) => {
      if (!(S.forced = S.forced || {})[partner]) { S.forced[partner] = await sim.lastSeq(partner); S.after(() => sim.reset(partner)); }
      await sim.scenario(partner, scenario);
    };
    /**
     * The partner holds every request past its caller's time limit, then refuses it (503) unapplied: a
     * timeout it did not survive. The limits follow the seeded ocs one (TIMERS=fast: 5 s; billagg and
     * c2wbank read limits 1 s above theirs, 27 s / 6 s), so: ocs limit + 5 s, others + 8 s.
     */
    S.lateUnapplied = partner => S.lateUnappliedMs(partner, ocsLimit() + (partner === 'ocs' ? 5000 : 8000));
    S.lateUnappliedMs = (partner, ms) => S.force(partner, { latencyMs: ms, errorRate: 1.0, outage: false, callbackMode: 'NONE', callbackDelayMs: 0 });
    /** Waits until the forced partner answered a request on payPath, then resets it (for the status inquiry). */
    S.normalOnceAnswered = async (partner, payPath) => {
      const since = (S.forced || {})[partner] || 0;
      const got = await poll(async () => (await sim.requests(partner)).filter(r => r.seq > since && r.method === 'POST' && r.path === payPath), l => l.length > 0, 60000, 1000);
      S.check(partner + ' answered ' + payPath + ' since it was forced', got.length > 0);
      await sim.reset(partner);
    };

    // app channel
    S.submit = async (user, body, idem) => {
      S.user = user;
      S.idem = idem || 'api-' + uuid();
      S.lastBody = body;
      S.lastSubmit = await app.prepare(user, S.idem, body);
      return S.record(await app.send(S.lastSubmit));
    };
    S.p2p = async (user, credit, amount, pin) => S.submit(user, await app.request('P2P', credit, amount, pin));
    S.txn = async (user, type, credit, amount, pin) => S.submit(user, await app.request(type, credit, amount, pin));
    S.topup = async (user, operator, product, msisdn, amount, pin) => {
      const b = await app.request('TOPUP', msisdn, amount, pin);
      b.creditParty.type = 'MSISDN';
      if (operator) b.partner = { partnerId: operator, productCode: product };
      return S.submit(user, b);
    };
    S.billPay = async (user, biller, bill, amount, pin) => {
      const b = await app.request('BILL_PAY', bill, amount, pin);
      b.creditParty.type = 'BILLER_ACCOUNT';
      if (biller) b.partner = { billerCode: biller };
      return S.submit(user, b);
    };
    S.addMoney = async (user, bank, account, amount, pin) => {
      const b = await app.request('ADD_MONEY_BANK', 'unused', amount, pin);
      delete b.creditParty;
      b.debitParty = { type: 'BANK_ACCOUNT', id: account };
      b.partner = { partnerId: bank };
      return S.submit(user, b);
    };
    S.repeat = async () => S.record(await app.send(await app.prepare(S.user, S.idem, S.lastBody)));
    S.appShows = async (st, within, error) => {
      const id = S.txnId();
      const r = await poll(() => app.status(S.user, id), x => x.body && x.body.status === st, (within || 20) * 1000, 400);
      S.eq('the app shows the transaction as ' + st, r.body && r.body.status, st, String(r));
      if (error) S.eq('with error ' + error, r.body.error && r.body.error.code, error, String(r));
    };

    // notifications
    S.sms = async (msisdn, text, within) => {
      text = text.replace('{txnId}', S.txnId());
      const got = await poll(async () => (await sim.sms(msisdn)).filter(m => m.text === text), l => l.length > 0 && !!l[0].dlrStatus, (within || 30) * 1000, 500);
      S.eq('one SMS to ' + msisdn + ' reading "' + text + '"', got.length, 1, got);
      S.eq('sent as Bits, delivered', [got[0].source, got[0].dlrStatus], ['Bits', 'DELIVRD']);
      return got[0];
    };
    S.mail = async (address, subject) => {
      subject = subject.replace('{txnId}', S.txnId());
      const got = await poll(async () => (await sim.mail(address)).filter(m => m.subject === subject), l => l.length > 0, 30000, 1000);
      S.eq('one email to ' + address + ' "' + subject + '"', got.length, 1);
      S.check('multipart/alternative with text and HTML', (got[0].contentType || '').startsWith('multipart/alternative')
        && (got[0].mimeMessage || '').includes('text/plain') && (got[0].mimeMessage || '').includes('text/html'), got[0].contentType);
    };
    S.noMail = async address => {
      const id = S.txnId();
      S.check('no email to ' + address + ' about the transaction', (await sim.mail(address)).every(m => !(m.subject || '').includes(id)));
    };

    // audit trail of a transaction (admin API detail, as an operator reads it)
    S.audit = async (until, within) => {
      const id = S.txnId();
      const r = await poll(() => admin.txn('ops-e2e-checker01', id), x => x.status === 200 && until(x.body.auditTrail || []), (within || 30) * 1000, 1000);
      return (r.body && r.body.auditTrail) || [];
    };
    const stateRows = rows => rows.filter(e => e.action === 'txn.state.changed');
    S.auditStates = async (states, once) => {
      const want = states.split(',').map(s => s.trim());
      const rows = stateRows(await S.audit(rs => stateRows(rs).length >= want.length));
      S.eq('the audit trail records the states ' + states + (once ? ' once each' : ''), rows.map(e => e.after && e.after.status), want);
      S.check('state rows are txn/<txnId>', rows.every(e => e.resource === 'txn/' + S.txnId()), rows);
      return rows;
    };
    S.auditPartnerCall = async service => {
      const rows = (await S.audit(rs => rs.some(e => e.action === 'partner.call.completed'))).filter(e => e.action === 'partner.call.completed');
      S.check('the audit trail records the partner call by ' + service, rows.length > 0 && rows.every(e => e.service === service
        && e.actor.type === 'system' && /^partner\/.*\/PAY$/.test(e.resource) && JSON.stringify(e.after || {}).includes('partnerOutcome')), rows);
    };
    const CB = ['partner.callback.late', 'txn.outcome.conflict'];
    S.auditCallback = async (action, auditOutcome, cbOutcome, within) => {
      const rows = (await S.audit(rs => rs.some(e => e.action === action && (e.after || {}).callbackOutcome === cbOutcome), within)).filter(e => CB.includes(e.action));
      S.check('the audit trail records a ' + action + ' ' + cbOutcome + ' callback', rows.some(e => e.action === action && e.outcome === auditOutcome
        && e.actor.type === 'partner' && e.actor.id === 'billagg' && (e.after || {}).callbackOutcome === cbOutcome), rows);
    };
    S.noLateCallback = async () => {
      const rows = (await S.audit(rs => rs.some(e => CB.includes(e.action)), 5)).filter(e => CB.includes(e.action));
      S.eq('the audit trail records no late callback', rows.length, 0, rows);
    };
    S.chainVerifies = async () => {
      const rows = await S.audit(rs => rs.length > 0);
      const months = [...new Set(rows.map(e => e.month || (e.at || '').slice(0, 7)))];
      S.check('audit rows of the transaction', months.length > 0, rows);
      for (const m of months) {
        const c = (await testApi.chain(m)).body;
        S.check('the audit hash chain of ' + m + ' verifies', c.intact === true, c);
      }
    };

    // USSD
    S.dial = async (mno, msisdn, code, inputs) => {
      const steps = inputs ? inputs.split(',').map(s => s.trim()) : [];
      S.session = await sim.ussd(mno, msisdn, code, steps);
      S.check('every USSD step answered 200', (S.session.screens || []).every(s => s.status === 200), S.session.screens);
      return S.session;
    };
    const lastScreen = () => S.session.screens[S.session.screens.length - 1];
    S.ussdEnds = text => {
      const l = lastScreen();
      S.check('the USSD session ends with "' + text + '"', S.session.ended === true && l.flag === 'END' && (l.message || '').startsWith(text), S.session.screens);
    };
    S.ussdOpenOn = text => {
      const l = lastScreen();
      S.check('the USSD session is still open on "' + text + '"', S.session.ended === false && l.flag === 'CON' && l.message === text, S.session.screens);
    };
    S.ussdOpenEndingWith = text => {
      const l = lastScreen();
      S.check('the USSD session is open on a screen ending "' + text + '"', S.session.ended === false && l.flag === 'CON' && (l.message || '').endsWith(text), S.session.screens);
    };
    S.ussdScreens = expected => {
      const shown = S.session.screens.map(s => s.message);
      S.eq('the first USSD screens', shown.slice(0, expected.length), expected);
    };

    // maker-checker in the admin API
    S.crFrom = r => { S.eq('change request created (201)', r.status, 201, String(r)); S.crId = r.body.id; return r; };
    S.crIs = async st => {
      const cr = (await admin.cr('ops-e2e-checker01', S.crId)).body || {};
      S.eq('the change request is ' + st, cr.status, st, cr);
    };
    S.adminAnswers = (r, status, code) => {
      S.eq('the admin API answers ' + status + ' ' + code, [r.status, r.body && r.body.code], [status, code], String(r));
    };
    S.adminShows = async (st, within) => {
      const id = S.txnId();
      const r = await poll(() => admin.txn('ops-e2e-checker01', id), x => x.body && x.body.transaction && x.body.transaction.status === st, (within || 5) * 1000, 700);
      S.eq('the admin view shows the transaction as ' + st, r.body && r.body.transaction && r.body.transaction.status, st, String(r));
    };
    S.auditDecision = async decision => {
      const action = 'admin.change_request.' + decision.toLowerCase();
      const rows = (await S.audit(rs => rs.filter(e => e.action === action).length === 1)).filter(e => e.action === action);
      S.eq('the audit trail records the ' + decision + ' change request once', rows.length, 1, rows);
      const e = rows[0];
      S.eq('before PENDING, after ' + decision + ', this change request', [(e.before || {}).status, (e.after || {}).status, (e.after || {}).changeRequestId], ['PENDING', decision, S.crId]);
    };
    return S;
  }

  /** Runs one scenario: every check is a pm.test; the first failed check or error stops it; after-hooks always run. */
  function run(name, fn, args) {
    const S = Scenario(name);
    const started = Date.now();
    (async () => {
      try {
        await fn(MW, S, args);
      } catch (e) {
        if (!(e instanceof Stop)) pm.test('scenario completes', () => { throw e instanceof Error ? e : new Error(String(e)); });
      } finally {
        for (const f of S.afterFns.reverse()) {
          try { await f(); } catch (e) { pm.test('clean-up', () => { throw e; }); }
        }
        console.log('scenario took ' + Math.round((Date.now() - started) / 1000) + ' s');
      }
    })();
  }

  const MW = {
    http, must, poll, sleep, uuid, unique, short, totp, claims, hmacHex, hmacB64, sha256Hex, encryptPin, token, bearer,
    core, sim, app, admin, portal, checkout, callbacks, testApi, run, FINAL, IPN_URL, v, CV,
    fixture: n => JSON.parse(CV.get('fixtures'))[n],
    ocsLimit, forRun, loginUser,
  };
  return MW;
})();

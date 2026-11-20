#!/usr/bin/env node
// Bank gateway profiles (ticket 117, FRS EXT-12): one partner route record per bank (`bank-<code>`),
// generated from the versioned bank-profiles.csv next to this file (code, name, protocol REST|SOAP,
// outbound auth hmac-sha256|api-key|basic|ws-security). Writes
//   services/mw-adapter-bank/src/main/resources/bank-profiles-dev.yml  (the adapter's dev seeds,
//     imported by its application-dev.yml; base URL BANK_GATEWAY_BASE_URL, hosts MW_BANK_ALLOWED_HOSTS)
//   services/mw-admin-api/src/main/resources/partner-seeds/dev/bank-<code>.json  (mw-admin-api's
//     copies, simulator URL written out) and the seed list between the markers of its application-dev.yml;
//   services/mw-simulators/src/main/resources/seed/v1/bank-profiles.csv  (the CSV the simulated bank
//     gateway serves, ticket 117d; a module never reads a path outside its own folder, so it gets a copy).
// Usage (from the repo root): node deploy/partner-routes/bank/generate.mjs [--check]
// --check writes nothing and exits 1 when a generated file differs (a test runs it).
// Wire format (mw-simulators' bank gateway, /bank/<code>/v1): REST POST accounts/verify {accountNumber}
// -> 200 {status: ACTIVE, accountName, verifyRef}; POST transfers {transactionId, accountNumber,
// amount, direction DEBIT|CREDIT} -> 200 SUCCESS / 202 PENDING {status, bankRef, code}; GET
// transfers/{reference}; POST transfers/{reference}/reverse {reversalId, amount}; 422 {code, message}
// refusals. SOAP 1.1 at /bank/<code>/v1/soap, namespace urn:bank:<code>:v1, the same fields as
// ValidateAccount, Transfer, TransferStatus, ReverseTransfer (and *Response); refusals are soap:Client
// faults with <detail><code/><message/></detail>. Health (ticket 117d, both protocols): GET health with the
// profile's HTTP auth (none for ws-security) -> 200 {status: UP}; probed on a schedule (healthCheck). Callbacks (both protocols) are the REST transfer body,
// hmac-sha256 signed with the field hmac-secret of secret/mw-adapter-bank/bank-<code>.
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..', '..', '..');
const check = process.argv.includes('--check');
const SIM = 'http://mw-simulators:8080';

const ERRORS = {
  4001: 'ACCOUNT_NOT_FOUND',
  4002: 'ACCOUNT_INACTIVE',
  4003: 'INSUFFICIENT_FUNDS',
  4004: 'LIMIT_EXCEEDED',
  4005: 'TRANSFER_REFUSED',
};
const AMBIGUOUS = ['9876'];

export function profiles(csv) {
  const [header, ...lines] = csv.trim().split('\n');
  if (header.trim() !== 'code,name,protocol,auth') throw new Error(`unexpected header: ${header}`);
  return lines.map((line) => {
    const [code, name, protocol, auth] = line.split(',').map((s) => s.trim());
    if (!/^[a-z0-9]{2,20}$/.test(code)) throw new Error(`bad code ${code}`);
    const ok = protocol === 'REST' ? ['hmac-sha256', 'api-key'] : protocol === 'SOAP' ? ['basic', 'ws-security'] : [];
    if (!ok.includes(auth)) throw new Error(`bank ${code}: ${protocol} with ${auth} is not supported`);
    return { code, name, protocol, auth };
  });
}

function restAuth(auth) {
  return auth === 'api-key' ? 'auth=apikey&secretField=api-key&authHeader=X-Api-Key' : 'auth=hmac-sha256&secretField=hmac-secret';
}

function soapAuth(auth) {
  return auth === 'basic' ? 'auth=basic&userField=basic-user&secretField=basic-password' : 'wsUsername=bits&wsPasswordSecret=ws-password';
}

/** The health probe's HTTP auth: the REST profile's own, Basic for a basic SOAP bank, none for ws-security. */
function healthAuth(p) {
  if (p.protocol === 'REST') return `&${restAuth(p.auth)}`;
  return p.auth === 'basic' ? '&auth=basic&userField=basic-user&secretField=basic-password' : '';
}

const HEALTH = `status = 200 and body.status = "UP" ? {"outcome": "SUCCESS", "partnerRef": "health"}
: {"outcome": "FAILED", "code": "HTTP_" & $string(status)}\n`;

function branch(op, steps) {
  return `                - simple: "\${header.mwOperation} == '${op}'"\n                  steps:\n${steps
    .map((s) => `                    - to: "${s}"`)
    .join('\n')}\n`;
}

function route(p, base) {
  const id = `bank-${p.code}`;
  let branches;
  if (p.protocol === 'REST') {
    const call = (method, path) => `kamelet:mw-http-call?url=${base}&method=${method}&path=/bank/${p.code}/v1/${path}&${restAuth(p.auth)}`;
    branches = [
      branch('INQUIRY', ['kamelet:mw-map?mapping=INQUIRY.request', call('POST', 'accounts/verify'),
        'kamelet:mw-classify?mapping=INQUIRY.response&definiteStatuses=200,422']),
      branch('PAY', ['kamelet:mw-map?mapping=PAY.request', call('POST', 'transfers'),
        'kamelet:mw-classify?mapping=PAY.response&definiteStatuses=400,422&pending=true']),
      branch('STATUS_QUERY', [call('GET', 'transfers/{reference}'),
        'kamelet:mw-classify?mapping=STATUS_QUERY.response&definiteStatuses=200,404&pending=true']),
      branch('REVERSE', ['kamelet:mw-map?mapping=REVERSE.request', call('POST', 'transfers/{reference}/reverse'),
        'kamelet:mw-classify?mapping=REVERSE.response&definiteStatuses=404,422']),
    ];
  } else {
    const call = (action) => `kamelet:mw-soap-call?address=${base}/bank/${p.code}/v1/soap&request=json&response=json`
      + `&namespace=urn:bank:${p.code}:v1&soapAction=urn:bank:v1:${action}&${soapAuth(p.auth)}`;
    const ops = [['INQUIRY', 'ValidateAccount', ''], ['PAY', 'Transfer', '&pending=true'],
      ['STATUS_QUERY', 'TransferStatus', '&pending=true'], ['REVERSE', 'ReverseTransfer', '']];
    branches = ops.map(([op, action, extra]) => branch(op, [`kamelet:mw-map?mapping=${op}.request`, call(action),
      `kamelet:mw-classify?mapping=${op}.response&definiteStatuses=200${extra}`]));
  }
  branches.push(branch('HEALTH_CHECK', [`kamelet:mw-http-call?url=${base}&method=GET&path=/bank/${p.code}/v1/health${healthAuth(p)}`,
    'kamelet:mw-classify?mapping=HEALTH_CHECK.response&definiteStatuses=503']));
  return `- route:\n    from:\n      uri: direct:partner-${id}\n      steps:\n        - choice:\n            when:\n${branches.join('')}        - to: "kamelet:mw-audit"\n`;
}

const fail = '{"outcome": "UNKNOWN", "reason": "MALFORMED_REPLY"}';
const masked = (name) => `$uppercase($substring($trim(${name}), 0, 1)) & "."`;

function restMappings() {
  const transfer = (b) => `${b}.status = "SUCCESS" and $type(${b}.bankRef) = "string" and ${b}.bankRef != ""
      ? {"outcome": "SUCCESS", "partnerRef": ${b}.bankRef, "code": ${b}.code}
    : ${b}.status = "PENDING"
      ? {"outcome": "PENDING", "partnerRef": ${b}.bankRef, "code": ${b}.code}`;
  return {
    'INQUIRY.request': '{"accountNumber": account}\n',
    // the holder name never leaves the adapter whole: first initial only (MaskingPolicy NAME)
    'INQUIRY.response': `status = 200 and body.status = "ACTIVE" and $type(body.accountName) = "string" and body.accountName != ""
    and $type(body.verifyRef) = "string" and body.verifyRef != ""
  ? {"outcome": "SUCCESS", "partnerRef": body.verifyRef, "attributes": {"holderName": ${masked('body.accountName')}}}
: status = 422 and $type(body.code) = "string" and body.code != ""
  ? {"outcome": "FAILED", "code": body.code, "message": body.message}
: ${fail}\n`,
    'PAY.request': '{"transactionId": reference, "accountNumber": account, "amount": amount.major, "direction": direction}\n',
    'PAY.response': `status = 200 or status = 202
  ? (${transfer('body')}
    : ${fail})
: status = 422 and $type(body.code) = "string" and body.code != ""
  ? {"outcome": "FAILED", "code": body.code, "message": body.message}
: status = 400
  ? {"outcome": "FAILED", "code": "HTTP_400", "message": body.message}
: {"outcome": "UNKNOWN", "reason": "UNEXPECTED_STATUS"}\n`,
    'STATUS_QUERY.response': `status = 200
  ? (${transfer('body')}
    : body.status = "FAILED" and $type(body.code) = "string" and body.code != ""
      ? {"outcome": "FAILED", "code": body.code, "message": body.message}
    : ${fail})
: status = 404 and body.status = "NOT_FOUND"
  ? {"outcome": "NOT_FOUND", "code": body.code, "message": body.message}
: {"outcome": "UNKNOWN", "reason": "UNEXPECTED_STATUS"}\n`,
    'REVERSE.request': '{"reversalId": reversalReference, "amount": amount.major}\n',
    'REVERSE.response': `status = 200
  ? (body.status = "REVERSED" and $type(body.reversalRef) = "string" and body.reversalRef != ""
      ? {"outcome": "SUCCESS", "partnerRef": body.reversalRef, "code": body.code}
      : ${fail})
: (status = 404 or status = 422) and $type(body.code) = "string" and body.code != ""
  ? {"outcome": "FAILED", "code": body.code, "message": body.message}
: {"outcome": "UNKNOWN", "reason": "UNEXPECTED_STATUS"}\n`,
  };
}

function soapMappings() {
  const fault = `body.fault.\`@code\` = "Client" and $type(body.fault.detail.code) = "string" and body.fault.detail.code != ""
  ? {"outcome": "FAILED", "code": body.fault.detail.code, "message": body.fault.detail.message}`;
  const transfer = (r) => `${r}.status = "SUCCESS" and $type(${r}.bankRef) = "string" and ${r}.bankRef != ""
  ? {"outcome": "SUCCESS", "partnerRef": ${r}.bankRef, "code": ${r}.code}
: ${r}.status = "PENDING"
  ? {"outcome": "PENDING", "partnerRef": ${r}.bankRef, "code": ${r}.code}`;
  return {
    'INQUIRY.request': '{"ValidateAccount": {"accountNumber": account}}\n',
    'INQUIRY.response': `body.ValidateAccountResponse.status = "ACTIVE" and $type(body.ValidateAccountResponse.accountName) = "string"
    and body.ValidateAccountResponse.accountName != "" and $type(body.ValidateAccountResponse.verifyRef) = "string"
  ? {"outcome": "SUCCESS", "partnerRef": body.ValidateAccountResponse.verifyRef,
     "attributes": {"holderName": ${masked('body.ValidateAccountResponse.accountName')}}}
: ${fault}
: ${fail}\n`,
    'PAY.request': '{"Transfer": {"transactionId": reference, "accountNumber": account, "amount": amount.major, "direction": direction}}\n',
    'PAY.response': `${transfer('body.TransferResponse')}
: ${fault}
: ${fail}\n`,
    'STATUS_QUERY.request': '{"TransferStatus": {"transactionId": reference}}\n',
    'STATUS_QUERY.response': `${transfer('body.TransferStatusResponse')}
: body.TransferStatusResponse.status = "FAILED" and $type(body.TransferStatusResponse.code) = "string"
  ? {"outcome": "FAILED", "code": body.TransferStatusResponse.code, "message": body.TransferStatusResponse.message}
: body.TransferStatusResponse.status = "NOT_FOUND"
  ? {"outcome": "NOT_FOUND", "code": body.TransferStatusResponse.code}
: ${fail}\n`,
    'REVERSE.request': '{"ReverseTransfer": {"transactionId": reference, "reversalId": reversalReference, "amount": amount.major}}\n',
    'REVERSE.response': `body.ReverseTransferResponse.status = "REVERSED" and $type(body.ReverseTransferResponse.reversalRef) = "string"
  ? {"outcome": "SUCCESS", "partnerRef": body.ReverseTransferResponse.reversalRef}
: ${fault}
: ${fail}\n`,
  };
}

const CALLBACK = `$type(body.transactionId) != "string" or body.transactionId = "" ? {}
: body.status = "SUCCESS" and $type(body.bankRef) = "string" and body.bankRef != ""
  ? {"outcome": "SUCCESS", "partnerRef": body.bankRef, "reference": body.transactionId,
     "code": body.code, "message": body.message}
: body.status = "FAILED" and $type(body.code) = "string" and body.code != ""
  ? {"outcome": "FAILED", "partnerRef": body.bankRef, "reference": body.transactionId,
     "code": body.code, "message": body.message}
: body.status = "PENDING"
  ? {"outcome": "PENDING", "partnerRef": body.bankRef, "reference": body.transactionId,
     "code": body.code, "message": body.message}
: {}\n`;

/** The record in mw-admin-api's camelCase seed shape. */
export function record(p, base) {
  return {
    enabled: true,
    connectTimeoutMs: 3000,
    // below the orchestrator's 30 s partner deadline; the read timeout a little above the limit
    timeoutMs: 25000,
    readTimeoutMs: 27000,
    inquiryRetries: 1,
    // callbacks are signed hmac-sha256 by every bank (the outbound auth is on the route)
    auth: { scheme: 'hmac-sha256', credentialsRef: 'hmac-secret' },
    sensitiveFields: { accountNumber: 'ACCOUNT', accountName: 'NAME' },
    ambiguousCodes: AMBIGUOUS,
    errorMap: Object.fromEntries(Object.entries(ERRORS).map(([c, s]) => [c, { suffix: s, retryable: false }])),
    route: route(p, base),
    // active health probe against the bank (ticket 117d): the adapter's health shows the bank, not only the route
    healthCheck: true,
    mappings: { ...(p.protocol === 'REST' ? restMappings() : soapMappings()), 'CALLBACK.response': CALLBACK,
      'HEALTH_CHECK.response': HEALTH },
  };
}

function adapterYaml(list) {
  const instances = {};
  for (const p of list) {
    const r = record(p, '${BANK_GATEWAY_BASE_URL:' + SIM + '}');
    instances[`bank-${p.code}`] = {
      family: 'bank',
      'allowed-hosts': '${MW_BANK_ALLOWED_HOSTS:mw-simulators}',
      'connect-timeout-ms': r.connectTimeoutMs,
      'timeout-ms': r.timeoutMs,
      'read-timeout-ms': r.readTimeoutMs,
      'inquiry-retries': r.inquiryRetries,
      auth: { scheme: r.auth.scheme, 'credentials-ref': r.auth.credentialsRef },
      'sensitive-fields': r.sensitiveFields,
      'ambiguous-codes': r.ambiguousCodes,
      'health-check': r.healthCheck,
      'error-map': Object.fromEntries(Object.entries(r.errorMap).map(([c, v]) => [`[${c}]`, v])),
      route: r.route,
      mappings: Object.fromEntries(Object.entries(r.mappings).map(([k, v]) => [`[${k}]`, v])),
    };
  }
  return '# GENERATED by deploy/partner-routes/bank/generate.mjs from bank-profiles.csv (ticket 117); do not edit.\n'
    + '# Dev seeds (version 0) of the bank gateway profiles, imported by application-dev.yml. JSON is YAML.\n'
    + JSON.stringify({ mw: { partners: { instances } } }, null, 2) + '\n';
}

function adminSeed(p) {
  const r = record(p, SIM);
  const { enabled, connectTimeoutMs, timeoutMs, readTimeoutMs, auth, sensitiveFields, ambiguousCodes, errorMap, healthCheck, route: rt, mappings } = r;
  return JSON.stringify({ partnerId: `bank-${p.code}`, family: 'bank', config: {
    enabled, connectTimeoutMs, timeoutMs, readTimeoutMs, auth, sensitiveFields, ambiguousCodes, errorMap, healthCheck,
    route: rt, mappings, allowedHosts: ['mw-simulators'] } }, null, 2) + '\n';
}

const BEGIN = '      # BEGIN bank profiles (generated by deploy/partner-routes/bank/generate.mjs)\n';
const END = '      # END bank profiles\n';

function adminList(current, list) {
  const block = BEGIN + list.map((p) => `      - classpath:partner-seeds/dev/bank-${p.code}.json\n`).join('') + END;
  const a = current.indexOf(BEGIN);
  const b = current.indexOf(END);
  if (a >= 0 && b > a) return current.slice(0, a) + block + current.slice(b + END.length);
  const anchor = '      - classpath:partner-seeds/dev/c2wbank.json\n';
  const i = current.indexOf(anchor);
  if (i < 0) throw new Error('mw-admin-api application-dev.yml: no c2wbank seed line to anchor the bank profiles');
  return current.slice(0, i + anchor.length) + block + current.slice(i + anchor.length);
}

const csv = readFileSync(join(here, 'bank-profiles.csv'), 'utf8');
const list = profiles(csv);
const outputs = new Map();
outputs.set(join(root, 'services/mw-simulators/src/main/resources/seed/v1/bank-profiles.csv'),
  '# GENERATED by deploy/partner-routes/bank/generate.mjs from deploy/partner-routes/bank/bank-profiles.csv\n'
  + '# (ticket 117d); do not edit. The banks of mw-simulators\' bank gateway (/bank/<code>/v1, mock party bank-<code>).\n'
  + csv.trim() + '\n');
outputs.set(join(root, 'services/mw-adapter-bank/src/main/resources/bank-profiles-dev.yml'), adapterYaml(list));
for (const p of list) {
  outputs.set(join(root, `services/mw-admin-api/src/main/resources/partner-seeds/dev/bank-${p.code}.json`), adminSeed(p));
}
const adminDev = join(root, 'services/mw-admin-api/src/main/resources/application-dev.yml');
outputs.set(adminDev, adminList(readFileSync(adminDev, 'utf8'), list));

let stale = 0;
for (const [path, text] of outputs) {
  const old = existsSync(path) ? readFileSync(path, 'utf8') : null;
  if (old === text) continue;
  if (check) {
    console.error(`stale: ${path.slice(root.length + 1)} (run node deploy/partner-routes/bank/generate.mjs)`);
    stale++;
  } else {
    writeFileSync(path, text);
    console.log(`wrote ${path.slice(root.length + 1)}`);
  }
}
process.exit(stale ? 1 : 0);

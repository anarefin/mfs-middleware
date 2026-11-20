#!/usr/bin/env node
// Bill aggregators (ticket 114, FRS UC-10, EXT-10, CON-02): one partner route record per aggregator,
// configuration only (no Java per partner). Each aggregator has its own payload style and auth:
//   sslcommerz  REST JSON, snake_case, hmac-sha256 signed with X-Key-Id (api key id + HMAC)
//   ekpay       REST JSON, camelCase, OAuth2 client credentials (token at /ekpay/v1/oauth/token)
//   agg3        SOAP 1.1 (mw-soap-call, the JSON/XML convention), namespace urn:agg3:v1, WS-Security
//   agg4        REST JSON with its own envelope ({"req": {...}} / {"res": {...}}), HTTP Basic
// Every record defines INQUIRY, PAY, STATUS_QUERY, REVERSE and a CALLBACK.response for the inbound
// payment-status callback (hmac-sha256 signed with the field hmac-secret, as billagg), and its own
// errorMap (MW-PTR-<AGG>-<suffix>). Writes
//   services/mw-adapter-billpay/src/main/resources/aggregators-dev.yml (the adapter's dev seeds,
//     imported by its application-dev.yml; base URL BILL_AGG_BASE_URL, hosts MW_BILL_AGG_ALLOWED_HOSTS)
//   services/mw-admin-api/src/main/resources/partner-seeds/dev/<id>.json and the seed list between the
//     markers of its application-dev.yml.
// Usage (from the repo root): node deploy/partner-routes/aggregators/generate.mjs [--check]
// --check writes nothing and exits 1 when a generated file differs (scripts/test/test_bill_aggregators.sh).
// Wire format: mw-simulators' BillAggregatorMockController under /<id>/v1/ (the mock party is the first path segment, ADR D33) (its CLAUDE.md "Bill
// aggregators": seeded bills seed/v1/bills/<id>.csv, stateful unpaid -> paid -> reversed).
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..', '..', '..');
const check = process.argv.includes('--check');
const SIM = 'http://mw-simulators:8080';
const fail = '{"outcome": "UNKNOWN", "reason": "MALFORMED_REPLY"}';
const unexpected = '{"outcome": "UNKNOWN", "reason": "UNEXPECTED_STATUS"}';

function branch(op, steps) {
  return `                - simple: "\${header.mwOperation} == '${op}'"\n                  steps:\n${steps
    .map((s) => `                    - to: "${s}"`)
    .join('\n')}\n`;
}

function wrap(id, branches) {
  return `- route:\n    from:\n      uri: direct:partner-${id}\n      steps:\n        - choice:\n            when:\n${branches.join('')}        - to: "kamelet:mw-audit"\n`;
}

/** A REST aggregator: the dialect names its fields, status words, paths and auth. */
function rest(a) {
  const call = (method, path) => `kamelet:mw-http-call?url=${a.base}&method=${method}&path=/${a.id}/v1/${path}&${a.auth}`;
  const route = wrap(a.id, [
    branch('INQUIRY', ['kamelet:mw-map?mapping=INQUIRY.request', call('POST', 'bills/inquiry'),
      'kamelet:mw-classify?mapping=INQUIRY.response&definiteStatuses=200,422']),
    branch('PAY', ['kamelet:mw-map?mapping=PAY.request', call('POST', 'payments'),
      'kamelet:mw-classify?mapping=PAY.response&definiteStatuses=400,422&pending=true']),
    branch('STATUS_QUERY', [call('GET', 'payments/{reference}'),
      'kamelet:mw-classify?mapping=STATUS_QUERY.response&definiteStatuses=200,404&pending=true']),
    branch('REVERSE', ['kamelet:mw-map?mapping=REVERSE.request', call('POST', 'payments/{reference}/reverse'),
      'kamelet:mw-classify?mapping=REVERSE.response&definiteStatuses=404,422']),
  ]);
  const f = a.fields;
  const res = (b) => (a.envelope ? `${b}.${a.envelope.res}` : b);
  const req = (o) => (a.envelope ? `{"${a.envelope.req}": ${o}}` : o);
  const B = res('body');
  const refused = `(status = 422 or status = 404) and $type(${B}.${f.code}) = "string" and ${B}.${f.code} != ""
  ? {"outcome": "FAILED", "code": ${B}.${f.code}, "message": ${B}.${f.message}}`;
  const payment = (b) => `${b}.${f.status} = "${a.words.paid}" and $type(${b}.${f.paymentRef}) = "string" and ${b}.${f.paymentRef} != ""
      ? {"outcome": "SUCCESS", "partnerRef": ${b}.${f.paymentRef}}
    : ${b}.${f.status} = "${a.words.pending}"
      ? {"outcome": "PENDING", "partnerRef": ${b}.${f.paymentRef}}`;
  const mappings = {
    'INQUIRY.request': req(`{"${f.billerCode}": partner.billerCode, "${f.billNumber}": creditParty.id}`) + '\n',
    // a paid bill is answered as such, never as due again (ticket 114: stateful bills)
    'INQUIRY.response': `status = 200 and ${B}.${f.status} = "${a.words.unpaid}" and $type(${B}.${f.amount}) = "string"
  ? {"outcome": "SUCCESS", "partnerRef": ${B}.${f.billNumber},
     "attributes": {"amount": ${B}.${f.amount}, "dueDate": ${B}.${f.dueDate}, "billPeriod": ${B}.${f.period}}}
: status = 200 and ${B}.${f.status} = "${a.words.paid}"
  ? {"outcome": "FAILED", "code": "${a.paidCode}", "message": "Bill already paid"}
: ${refused}
: ${unexpected}\n`,
    'PAY.request': req(`{"${f.reference}": reference, "${f.billerCode}": partner.billerCode, "${f.billNumber}": creditParty.id,
 "${f.amount}": amount.major}`) + '\n',
    'PAY.response': `status = 200 or status = 202
  ? (${payment(B)}
    : ${fail})
: ${refused}
: status = 400
  ? {"outcome": "FAILED", "code": "HTTP_400"}
: ${unexpected}\n`,
    // NOT_FOUND only for a 404 carrying the aggregator's own NOT_FOUND marker: a bare gateway or load
    // balancer 404 stays UNKNOWN, or a paid bill's hold would be released (M9 review)
    'STATUS_QUERY.response': `status = 200
  ? (${payment(B)}
    : ${B}.${f.status} = "${a.words.failed}" and $type(${B}.${f.code}) = "string"
      ? {"outcome": "FAILED", "code": ${B}.${f.code}, "message": ${B}.${f.message}}
    : ${fail})
: status = 404 and ${B}.${f.status} = "NOT_FOUND"
  ? {"outcome": "NOT_FOUND", "code": ${B}.${f.code}}
: ${unexpected}\n`,
    'REVERSE.request': req(`{"${f.reversalId}": reversalReference, "${f.amount}": amount.major}`) + '\n',
    'REVERSE.response': `status = 200
  ? (${B}.${f.status} = "${a.words.reversed}" and $type(${B}.${f.reversalRef}) = "string" and ${B}.${f.reversalRef} != ""
      ? {"outcome": "SUCCESS", "partnerRef": ${B}.${f.reversalRef}}
      : ${fail})
: ${refused}
: ${unexpected}\n`,
  };
  return { route, mappings };
}

function soap(a) {
  const call = (action) => `kamelet:mw-soap-call?address=${a.base}/${a.id}/v1/soap&request=json&response=json`
    + `&namespace=urn:${a.id}:v1&soapAction=urn:${a.id}:v1:${action}&wsUsername=bits&wsPasswordSecret=ws-password`;
  const ops = [['INQUIRY', 'GetBill', ''], ['PAY', 'PayBill', '&pending=true'],
    ['STATUS_QUERY', 'PaymentStatus', '&pending=true'], ['REVERSE', 'ReversePayment', '']];
  const route = wrap(a.id, ops.map(([op, action, extra]) => branch(op, [`kamelet:mw-map?mapping=${op}.request`, call(action),
    `kamelet:mw-classify?mapping=${op}.response&definiteStatuses=200${extra}`])));
  const fault = `body.fault.\`@code\` = "Client" and $type(body.fault.detail.code) = "string" and body.fault.detail.code != ""
  ? {"outcome": "FAILED", "code": body.fault.detail.code, "message": body.fault.detail.message}`;
  const payment = (r) => `${r}.status = "PAID" and $type(${r}.receiptNo) = "string" and ${r}.receiptNo != ""
  ? {"outcome": "SUCCESS", "partnerRef": ${r}.receiptNo}
: ${r}.status = "PENDING"
  ? {"outcome": "PENDING", "partnerRef": ${r}.receiptNo}`;
  const mappings = {
    'INQUIRY.request': '{"GetBill": {"billerCode": partner.billerCode, "billNumber": creditParty.id}}\n',
    'INQUIRY.response': `body.GetBillResponse.status = "UNPAID" and $type(body.GetBillResponse.amountDue) = "string"
  ? {"outcome": "SUCCESS", "partnerRef": body.GetBillResponse.billNumber,
     "attributes": {"amount": body.GetBillResponse.amountDue, "dueDate": body.GetBillResponse.dueDate,
       "billPeriod": body.GetBillResponse.billPeriod}}
: body.GetBillResponse.status = "PAID"
  ? {"outcome": "FAILED", "code": "${a.paidCode}", "message": "Bill already paid"}
: ${fault}
: ${fail}\n`,
    'PAY.request': '{"PayBill": {"transactionId": reference, "billerCode": partner.billerCode, "billNumber": creditParty.id, "amount": amount.major}}\n',
    'PAY.response': `${payment('body.PayBillResponse')}
: ${fault}
: ${fail}\n`,
    'STATUS_QUERY.request': '{"PaymentStatus": {"transactionId": reference}}\n',
    'STATUS_QUERY.response': `${payment('body.PaymentStatusResponse')}
: body.PaymentStatusResponse.status = "FAILED" and $type(body.PaymentStatusResponse.code) = "string"
  ? {"outcome": "FAILED", "code": body.PaymentStatusResponse.code, "message": body.PaymentStatusResponse.message}
: body.PaymentStatusResponse.status = "NOT_FOUND"
  ? {"outcome": "NOT_FOUND", "code": body.PaymentStatusResponse.code}
: ${fault}
: ${fail}\n`,
    'REVERSE.request': '{"ReversePayment": {"transactionId": reference, "reversalId": reversalReference, "amount": amount.major}}\n',
    'REVERSE.response': `body.ReversePaymentResponse.status = "REVERSED" and $type(body.ReversePaymentResponse.reversalRef) = "string"
  ? {"outcome": "SUCCESS", "partnerRef": body.ReversePaymentResponse.reversalRef}
: ${fault}
: ${fail}\n`,
  };
  return { route, mappings };
}

/** The callback (every aggregator posts the same JSON shape as its REST status answer). */
function callback(a) {
  const f = a.fields;
  return `$type(body.${f.reference}) != "string" or body.${f.reference} = "" ? {}
: body.${f.status} = "${a.words.paid}" and $type(body.${f.paymentRef}) = "string" and body.${f.paymentRef} != ""
  ? {"outcome": "SUCCESS", "partnerRef": body.${f.paymentRef}, "reference": body.${f.reference}}
: body.${f.status} = "${a.words.failed}" and $type(body.${f.code}) = "string" and body.${f.code} != ""
  ? {"outcome": "FAILED", "partnerRef": body.${f.paymentRef}, "reference": body.${f.reference},
     "code": body.${f.code}, "message": body.${f.message}}
: {}\n`;
}

const SNAKE = { billerCode: 'biller_code', billNumber: 'bill_no', amount: 'amount', dueDate: 'due_date', period: 'bill_period',
  status: 'status', reference: 'tran_id', paymentRef: 'val_id', reversalId: 'refund_ref', reversalRef: 'refund_ref_id',
  code: 'error_code', message: 'error_message' };
const CAMEL = { billerCode: 'billerId', billNumber: 'billNo', amount: 'amount', dueDate: 'dueDate', period: 'billMonth',
  status: 'status', reference: 'requestId', paymentRef: 'trxId', reversalId: 'reversalId', reversalRef: 'reversalTrxId',
  code: 'respCode', message: 'respMsg' };
const SOAPF = { billerCode: 'billerCode', billNumber: 'billNumber', amount: 'amount', status: 'status',
  reference: 'transactionId', paymentRef: 'receiptNo', code: 'code', message: 'message' };
const AGG4 = { billerCode: 'svc', billNumber: 'acct', amount: 'amt', dueDate: 'due', period: 'month', status: 'state',
  reference: 'ref', paymentRef: 'rcpt', reversalId: 'rev_ref', reversalRef: 'rev_rcpt', code: 'err', message: 'msg' };

export const AGGREGATORS = [
  { id: 'sslcommerz', style: 'rest', fields: SNAKE, paidCode: 'E102',
    words: { unpaid: 'UNPAID', paid: 'VALID', pending: 'PENDING', failed: 'FAILED', reversed: 'REFUNDED' },
    auth: 'auth=hmac-sha256&secretField=hmac-secret&keyId=bits-store',
    errors: { E101: 'BILL_NOT_FOUND', E102: 'BILL_ALREADY_PAID', E103: 'PAYMENT_REFUSED', E104: 'UNKNOWN_BILLER',
      E105: 'NOT_PAID', E106: 'AMOUNT_MISMATCH' } },
  { id: 'ekpay', style: 'rest', fields: CAMEL, paidCode: '402',
    words: { unpaid: 'DUE', paid: 'SUCCESS', pending: 'PROCESSING', failed: 'FAILED', reversed: 'REVERSED' },
    auth: 'auth=oauth2-cc&secretField=client-secret&clientId=bits-mw&scope=bill.pay&tokenUrl=BASE/ekpay/v1/oauth/token',
    errors: { 401: 'BILL_NOT_FOUND', 402: 'BILL_ALREADY_PAID', 403: 'PAYMENT_REFUSED', 404: 'UNKNOWN_BILLER',
      405: 'NOT_PAID', 406: 'AMOUNT_MISMATCH' } },
  { id: 'agg3', style: 'soap', fields: SOAPF, paidCode: 'A3-02',
    words: { paid: 'PAID', failed: 'FAILED' },
    errors: { 'A3-01': 'BILL_NOT_FOUND', 'A3-02': 'BILL_ALREADY_PAID', 'A3-03': 'PAYMENT_REFUSED', 'A3-04': 'UNKNOWN_BILLER',
      'A3-05': 'NOT_PAID', 'A3-06': 'AMOUNT_MISMATCH' } },
  { id: 'agg4', style: 'rest', fields: AGG4, paidCode: 'X02', envelope: { req: 'req', res: 'res' },
    words: { unpaid: 'OPEN', paid: 'DONE', pending: 'WAIT', failed: 'FAIL', reversed: 'VOID' },
    auth: 'auth=basic&userField=basic-user&secretField=basic-password',
    errors: { X01: 'BILL_NOT_FOUND', X02: 'BILL_ALREADY_PAID', X03: 'PAYMENT_REFUSED', X04: 'UNKNOWN_BILLER',
      X05: 'NOT_PAID', X06: 'AMOUNT_MISMATCH' } },
];

/** The record in mw-admin-api's camelCase seed shape. */
export function record(a, base) {
  const agg = { ...a, base, auth: a.auth ? a.auth.replace('BASE', base) : a.auth };
  const { route, mappings } = a.style === 'soap' ? soap(agg) : rest(agg);
  return {
    enabled: true,
    connectTimeoutMs: 3000,
    // below the orchestrator's 30 s partner deadline; the read timeout a little above the limit
    timeoutMs: 25000,
    readTimeoutMs: 27000,
    inquiryRetries: 1,
    // callbacks are signed hmac-sha256 by every aggregator (the outbound auth is on the route)
    auth: { scheme: 'hmac-sha256', credentialsRef: 'hmac-secret' },
    sensitiveFields: { [a.fields.billNumber]: 'ACCOUNT' },
    ambiguousCodes: ['9876'],
    errorMap: Object.fromEntries(Object.entries(a.errors).map(([c, s]) => [c, { suffix: s, retryable: false }])),
    route,
    mappings: { ...mappings, 'CALLBACK.response': callback(a) },
  };
}

function adapterYaml() {
  const instances = {};
  for (const a of AGGREGATORS) {
    const r = record(a, '${BILL_AGG_BASE_URL:' + SIM + '}');
    instances[a.id] = {
      family: 'billpay',
      'allowed-hosts': '${MW_BILL_AGG_ALLOWED_HOSTS:mw-simulators}',
      'connect-timeout-ms': r.connectTimeoutMs,
      'timeout-ms': r.timeoutMs,
      'read-timeout-ms': r.readTimeoutMs,
      'inquiry-retries': r.inquiryRetries,
      auth: { scheme: r.auth.scheme, 'credentials-ref': r.auth.credentialsRef },
      'sensitive-fields': r.sensitiveFields,
      'ambiguous-codes': r.ambiguousCodes,
      'error-map': Object.fromEntries(Object.entries(r.errorMap).map(([c, v]) => [`[${c}]`, v])),
      route: r.route,
      mappings: Object.fromEntries(Object.entries(r.mappings).map(([k, v]) => [`[${k}]`, v])),
    };
  }
  return '# GENERATED by deploy/partner-routes/aggregators/generate.mjs (ticket 114); do not edit.\n'
    + '# Dev seeds (version 0) of the bill aggregators, imported by application-dev.yml. JSON is YAML.\n'
    + JSON.stringify({ mw: { partners: { instances } } }, null, 2) + '\n';
}

function adminSeed(a) {
  const r = record(a, SIM);
  return JSON.stringify({ partnerId: a.id, family: 'billpay', config: { ...r, allowedHosts: ['mw-simulators'] } }, null, 2) + '\n';
}

const BEGIN = '      # BEGIN bill aggregators (generated by deploy/partner-routes/aggregators/generate.mjs)\n';
const END = '      # END bill aggregators\n';

function adminList(current) {
  const block = BEGIN + AGGREGATORS.map((a) => `      - classpath:partner-seeds/dev/${a.id}.json\n`).join('') + END;
  const i = current.indexOf(BEGIN);
  const j = current.indexOf(END);
  if (i >= 0 && j > i) return current.slice(0, i) + block + current.slice(j + END.length);
  const anchor = '      - classpath:partner-seeds/dev/billagg.json\n';
  const k = current.indexOf(anchor);
  if (k < 0) throw new Error('mw-admin-api application-dev.yml: no billagg seed line to anchor the aggregators');
  return current.slice(0, k + anchor.length) + block + current.slice(k + anchor.length);
}

const outputs = new Map();
outputs.set(join(root, 'services/mw-adapter-billpay/src/main/resources/aggregators-dev.yml'), adapterYaml());
for (const a of AGGREGATORS) {
  outputs.set(join(root, `services/mw-admin-api/src/main/resources/partner-seeds/dev/${a.id}.json`), adminSeed(a));
}
const adminDev = join(root, 'services/mw-admin-api/src/main/resources/application-dev.yml');
outputs.set(adminDev, adminList(readFileSync(adminDev, 'utf8')));

let stale = 0;
for (const [path, text] of outputs) {
  const old = existsSync(path) ? readFileSync(path, 'utf8') : null;
  if (old === text) continue;
  if (check) {
    console.error(`stale: ${path.slice(root.length + 1)} (run node deploy/partner-routes/aggregators/generate.mjs)`);
    stale++;
  } else {
    writeFileSync(path, text);
    console.log(`wrote ${path.slice(root.length + 1)}`);
  }
}
process.exit(stale ? 1 : 0);

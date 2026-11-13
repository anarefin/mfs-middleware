#!/usr/bin/env node
// Picks the run customer of one scripts/api-test.sh run (tests/api/run.sh) and records the pick:
//
//   node tests/api/run_customer.mjs MANIFEST LEDGER [YYYY-MM-DD]     prints "<username> <msisdn>"
//
// The scenarios pay mostly from customer01 (01711000001). One run spends about 4,000 BDT of a wallet's
// daily debit limit in mw-core-mock (15,000 BDT per UTC day), so a 4th run on one day would fail with
// MW-CORE-LIMIT_EXCEEDED_DAILY. Instead every run pays from one of mw-seeder's run customers
// (persona "run-customer" in the seed manifest: own Keycloak user and wallet, MSISDN 0171????001 so
// it masks like customer01): the one used least on this UTC day (ties: the first), at most
// RUNS_PER_DAY times a day (3 x 4,000 < 15,000). LEDGER (build/api-test/run-customers.tsv) holds one
// "<day>\t<username>" line per pick; a partial run counts as a full one. lib.js then plays customer01
// as that customer (MW.forRun). The day defaults to today (UTC).
import { readFileSync, appendFileSync, existsSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

const RUNS_PER_DAY = 3;
const [manifestPath, ledgerPath, dayArg] = process.argv.slice(2);
if (!manifestPath || !ledgerPath) {
  console.error('usage: run_customer.mjs MANIFEST LEDGER [YYYY-MM-DD]');
  process.exit(2);
}
const day = dayArg || new Date().toISOString().slice(0, 10);
const pool = (JSON.parse(readFileSync(manifestPath, 'utf8')).personas || [])
  .filter(p => p.persona === 'run-customer' && p.realm === 'customers' && p.attributes && p.attributes.msisdn);
if (!pool.length) {
  console.error(`run_customer: the seed manifest ${manifestPath} has no run customers: start the stack with --profile seed (mw-seeder must exit 0)`);
  process.exit(1);
}
const used = new Map(pool.map(p => [p.username, 0]));
if (existsSync(ledgerPath)) {
  for (const line of readFileSync(ledgerPath, 'utf8').split('\n')) {
    const [d, user] = line.split('\t');
    if (d === day && used.has(user)) used.set(user, used.get(user) + 1);
  }
}
const pick = pool.reduce((best, p) => (used.get(p.username) < used.get(best.username) ? p : best), pool[0]);
if (used.get(pick.username) >= RUNS_PER_DAY) {
  console.error(`run_customer: all ${pool.length} run customers already ran ${RUNS_PER_DAY} times on ${day} (UTC); ` +
    'wait for the next UTC day, or start a fresh stack (deploy/reset.sh, then up with test-timers.env and --profile seed) and delete ' + ledgerPath);
  process.exit(1);
}
mkdirSync(dirname(ledgerPath), { recursive: true });
appendFileSync(ledgerPath, `${day}\t${pick.username}\n`);
console.log(`${pick.username} ${pick.attributes.msisdn}`);

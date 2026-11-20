// Ticket 123 (FRS EXT-05, EXT-06, EXT-17, MCT-05): the file mocks of mw-simulators through the test-only route
// /test/mock-control/*. They work on partner-sftp (compose profile tester); every scenario puts its party back.
export const helpers = [
  async function runFileMock(MW, party, body) {
    return MW.http('POST', '/test/mock-control/' + party + '/run', body ? { apikey: true, json: body } : { apikey: true });
  },
  async function dropFile(MW, party, name, content) {
    return MW.http('POST', '/test/mock-control/' + party + '/files', { apikey: true, json: { name, content } });
  },
  /** An AML transaction file in the draft format of contracts/files/aml/README.md: header, rows, trailer. */
  function amlFile(MW, rows) {
    let body = 'H|AML_TXN|1|' + MW.unique() + '|2026-11-20T10:00:00+06:00\n';
    let total = 0;
    for (const r of rows) {
      body += r + '\n';
      total += Number(r.split('|')[6]);
    }
    return body + 'T|' + rows.length + '|' + total + '|' + MW.sha256Hex(body) + '\n';
  },
];

export default [
  {
    name: '[mock] the trust bank writes an MT940 on request and the journal lists it with its checksum',
    run: async (MW, S) => {
      await MW.mock.reset('trustbank');
      const r = await runFileMock(MW, 'trustbank', { operation: 'mt940', date: '2026-01-15' });
      S.status(200, r);
      const written = ((r.body && r.body.entries) || []).find(e => e.method === 'WRITE');
      S.check('one statement was written', !!written, r.body);
      S.eq('it is the MT940 of the day', written.path, 'trustbank/out/MT940_0011223344556_20260115.fin');
      S.check('it is a FIN MT940 (:20: TB940260115, :60F:, :62F:)',
        /:20:TB940260115/.test(written.body) && /:60F:/.test(written.body) && /:62F:/.test(written.body), written.body);
      S.eq('its checksum is the sha256 of the file', written.headers.sha256, MW.sha256Hex(written.body));
      const journal = await MW.mock.journal('trustbank', { path: 'MT940_' });
      S.check('the journal holds that very file', journal.some(e => e.seq === written.seq && e.headers.sha256 === written.headers.sha256), journal);
    },
  },
  {
    name: '[mock] a MISSING_FILE scenario skips the next statement only and an injected mismatch reaches the one after',
    run: async (MW, S) => {
      S.after(() => MW.mock.reset('trustbank'));
      await MW.mock.reset('trustbank');
      await MW.mock.scenario('trustbank', { operation: 'mt942', behaviour: 'MISSING_FILE', nextN: 1 });
      const skipped = await runFileMock(MW, 'trustbank', { operation: 'mt942', date: '2026-01-15', at: '10:00' });
      S.status(200, skipped);
      S.eq('the first MT942 is not delivered', skipped.body.entries.map(e => e.method), ['SKIP']);
      S.eq('the journal names the fault', skipped.body.entries[0].headers.fault, 'MISSING_FILE');

      await MW.mock.scenario('trustbank', { operation: 'mt942', behaviour: 'INJECTED_MISMATCH', code: 'MISSING_IN_UPAY', count: 2, nextN: 1 });
      const late = await runFileMock(MW, 'trustbank', { operation: 'mt942', date: '2026-01-15', at: '10:15' });
      S.status(200, late);
      const w = late.body.entries[0];
      S.eq('the next one is written', w.method, 'WRITE');
      S.eq('with the injected mismatch', w.headers.fault, 'INJECTED_MISMATCH:MISSING_IN_UPAY:2');
      S.check('two statement lines no ledger has', /TB-EXTRA-260115-1/.test(w.body) && /TB-EXTRA-260115-2/.test(w.body), w.body);
      const clean = await runFileMock(MW, 'trustbank', { operation: 'mt942', date: '2026-01-15', at: '10:30' });
      S.check('the third is clean again', !clean.body.entries[0].headers.fault, clean.body);
    },
  },
  {
    name: '[mock] the AML mock answers an uploaded transaction file with HOLD and BLOCK lines',
    run: async (MW, S) => {
      await MW.mock.reset('aml');
      const name = 'AML_TXN_' + MW.unique() + '.txt';
      const file = amlFile(MW, [
        'D|' + MW.uuid() + '|P2P|APP|WALLET:01711000777|WALLET:01812345678|1000|2026-11-20T09:00:00+06:00',
        'D|' + MW.uuid() + '|P2P|APP|WALLET:01712345678|WALLET:01811116666|1000|2026-11-20T09:01:00+06:00',
        'D|' + MW.uuid() + '|P2P|APP|WALLET:01712345678|WALLET:01812345678|1000|2026-11-20T09:02:00+06:00',
      ]);
      const drop = await dropFile(MW, 'aml', name, file);
      S.status(201, drop);
      S.eq('the file is in the AML inbox', drop.body.path, 'aml/in/' + name);

      const r = await runFileMock(MW, 'aml');
      S.status(200, r);
      const read = r.body.entries.find(e => e.method === 'READ' && e.path === 'aml/in/' + name);
      S.check('the mock read the file', !!read && read.headers.sha256 === MW.sha256Hex(file), r.body);
      const resp = r.body.entries.find(e => e.method === 'WRITE');
      S.eq('it wrote the response file', resp.path, 'aml/out/' + name.replace('AML_TXN_', 'AML_RESP_'));
      const lines = resp.body.trim().split('\n');
      S.check('a HOLD for the watch-listed number', lines.some(l => /^HOLD\|.*\|01711000777\|WATCH_LIST\|/.test(l)), lines);
      S.check('a BLOCK for the number ending 6666', lines.some(l => /^BLOCK\|.*\|01811116666\|BLOCK_SUFFIX\|/.test(l)), lines);
      S.eq('the trailer counts two lines, no alert, one hold, one block', lines[lines.length - 1], 'T|2|0|1|1');
    },
  },
  {
    name: '[mock] the DW drop check rejects a control file with a wrong row count',
    run: async (MW, S) => {
      await MW.mock.reset('dw');
      const day = '2026-01-' + String(10 + Math.floor(Math.random() * 18)).padStart(2, '0');
      const csv = 'txn_id,type,status,amount_minor,currency,channel,payer_token,payee_token,created_at,completed_at\n'
        + 'T1,P2P,COMPLETED,100,BDT,APP,tok1,tok2,' + day + 'T09:00:00+06:00,' + day + 'T09:00:01+06:00\n';
      S.status(201, await dropFile(MW, 'dw', 'transactions_' + day + '.csv', csv));
      const control = JSON.stringify({ date: day, files: [{ name: 'transactions_' + day + '.csv', rows: 3, sha256: MW.sha256Hex(csv) }] });
      S.status(201, await dropFile(MW, 'dw', 'control_' + day + '.json', control));

      const r = await runFileMock(MW, 'dw');
      S.status(200, r);
      const verdict = (await MW.mock.journal('dw', { path: 'control_' + day })).pop();
      S.eq('the drop is rejected', verdict && verdict.headers.verdict, 'REJECTED');
      S.eq('for its row count', verdict.headers.reasons, 'ROW_COUNT transactions_' + day + '.csv: control 3, file 1');
    },
  },
];

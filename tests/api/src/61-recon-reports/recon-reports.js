// Ticket 127c (FRS REC-07): the daily trust account position, run on demand through the test-only route.
export default [
  {
    name: "[mock] the trust position job compares the core e-money total with the trust bank's closing balance",
    run: async (MW, S) => {
      S.after(() => MW.mock.reset('trustbank'));
      await MW.mock.reset('trustbank');
      const written = await MW.http('POST', '/test/mock-control/trustbank/run', {
        apikey: true, json: { operation: 'mt940' },
      });
      S.status(200, written);
      const pulled = await MW.http('POST', '/test/recon/v1/pull', { apikey: true });
      S.status(200, pulled);
      const position = await MW.http('POST', '/test/recon/v1/trust-position', { apikey: true });
      S.status(200, position);
      const p = position.body || {};
      S.check('the core reports e-money', Number(p.emoneyTotalMinor) > 0, p);
      S.eq('the difference is trust minus e-money', p.differenceMinor, p.trustClosingMinor - p.emoneyTotalMinor);
      S.check('a difference raises an exception, none without one',
        (p.differenceMinor === 0) === (p.exceptionId === null || p.exceptionId === undefined), p);
    },
  },
  {
    name: "[mock] a trust position for a day without a statement is 404 MW-VAL-003",
    run: async (MW, S) => {
      const none = await MW.http('POST', '/test/recon/v1/trust-position?date=2000-01-01', { apikey: true });
      S.status(404, none);
      S.eq('the error code', none.body && none.body.code, 'MW-VAL-003');
    },
  },
];

// Ticket 128 (FRS EXT-17, DAT-02): today's warehouse drop, checked by the dw mock.
export default [
  {
    name: "[mock] today's data warehouse export is accepted by the drop check",
    run: async (MW, S) => {
      const exported = await MW.http('POST', '/test/data-export/v1/exports/today', { apikey: true });
      S.status(200, exported);
      const day = exported.body && exported.body.date;
      S.check('the export names the day', !!day, exported.body);
      await MW.mock.reset('dw');
      const run = await MW.http('POST', '/test/mock-control/dw/run', { apikey: true });
      S.status(200, run);
      const verdict = (await MW.mock.journal('dw', { path: 'control_' + day })).pop();
      S.eq('the drop is accepted', verdict && verdict.headers.verdict, 'ACCEPTED');
    },
  },
];

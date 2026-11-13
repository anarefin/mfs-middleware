// Ticket 58 names it for this folder: the full retry-to-DLQ path (spec 19.2). No Cucumber scenario
// had it (the retry topics were proved only by the modules' RetryTopicRouteIT), so PARITY.md lists it
// as an addition. A record mw-orchestrator always refuses walks retry.1..3 to its DLQ: about 3 s with
// the test timers (MW_KAFKA_RETRY_DELAYS 1s,1s,1s), some 11 min with the production delays (10 s, 1 min,
// 10 min), hence folder 90.
export default [
  {
    name: '[dlq] a request the orchestrator always refuses is retried three times and parked on its DLQ, nothing stored',
    run: async (MW, S) => {
      const sent = S.record(await MW.testApi.poison());
      S.status(202);
      const { txnId, dlqTopic } = sent.body;
      S.eq('it is headed for the mw.txn.request.v1 DLQ of mw-orchestrator', dlqTopic, 'mw.txn.request.v1.mw-orchestrator.dlq');
      const parked = await MW.poll(() => MW.testApi.dlqRecord(txnId), r => r.status === 200, 900000, 2000);
      S.eq('within 15 minutes the record is on the DLQ under its key', [parked.status, parked.body && parked.body.parked], [200, true], String(parked));
      S.eq('the DLQ answer names the topic', parked.body.topic, dlqTopic);
      // refused before the intake on every attempt: no idempotency record, so no transaction
      const handled = await MW.testApi.handled('test-poison-' + txnId);
      S.eq('the orchestrator never took the request in', handled.status, 404, String(handled));
      const other = await MW.testApi.dlqRecord('00000000-0000-4000-8000-000000000000');
      S.eq('a key that was never sent is not on the DLQ', other.status, 404, String(other));
    },
  },
];

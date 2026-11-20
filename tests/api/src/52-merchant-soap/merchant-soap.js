// Ticket 115b (FRS UC-11, EXT-11). merchant20 (01911000020) has its own merchant system: the dev
// orchestrator maps it to partner merchant-soap (MerchantSoapMock merchant M-0001, till TILL-01).
// The mock party merchant-soap journals every SOAP call (ValidateMerchant, NotifyPayment, ...).
// soapCalls and merchantWallet are helpers: the collection builder inlines them into each script.
async function soapCalls(MW, element) {
  return (await MW.mockJournal('merchant-soap', { contains: element })) || [];
}
function merchantWallet() {
  return '01911000020';
}
export const helpers = [soapCalls, merchantWallet];

export default [
  {
    name: '[merchant frs] T1 a payment to a merchant with its own system is validated there, completes and the merchant is notified',
    run: async (MW, S) => {
      S.after(() => MW.mockReset('merchant-soap'));
      await MW.mockReset('merchant-soap');
      await S.note(['01711000001', merchantWallet(), 'FEE_INCOME']);
      await S.txn('customer01', 'MERCHANT_PAY', merchantWallet(), 2500, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.changedBy({ '01711000001': -2500, [merchantWallet()]: 2500, 'FEE_INCOME': 0 });
      const validated = await soapCalls(MW, 'ValidateMerchant');
      S.check('the merchant system validated merchant M-0001 and its till', validated.some(e => String(e.body).includes('M-0001') && String(e.body).includes('TILL-01')), JSON.stringify(validated).slice(0, 400));
      const notified = await MW.poll(() => soapCalls(MW, 'NotifyPayment'), l => l.length > 0, 20000, 500);
      S.check('the merchant system received the payment notify', notified.length === 1, JSON.stringify(notified).slice(0, 400));
      S.check('the notify carries the amount', String((notified[0] || {}).body).includes('25.00'), JSON.stringify(notified).slice(0, 400));
    },
  },
  {
    name: '[merchant frs] T9 an inactive merchant is refused by the merchant system before any money moves',
    run: async (MW, S) => {
      S.after(() => MW.mockReset('merchant-soap'));
      await MW.mockScenario('merchant-soap', 'validate', 'BUSINESS_ERROR', { code: '5002', nextN: 1 });
      await S.note(['01711000001', merchantWallet(), 'FEE_INCOME']);
      await S.txn('customer01', 'MERCHANT_PAY', merchantWallet(), 2500, MW.pin);
      S.status(502);
      S.code('MW-PTR-MERCHANT_SOAP-MERCHANT_INACTIVE');
      await S.noChange();
      const notified = await soapCalls(MW, 'NotifyPayment');
      S.check('no notify went to the merchant system', notified.length === 0, JSON.stringify(notified).slice(0, 400));
    },
  },
  {
    name: '[merchant frs] T3 a merchant system 5XX on the notify is retried and the payment stays COMPLETED',
    run: async (MW, S) => {
      S.after(() => MW.mockReset('merchant-soap'));
      await MW.mockReset('merchant-soap');
      await MW.mockScenario('merchant-soap', 'notify', 'HTTP_5XX', { nextN: 1 });
      await S.note(['01711000001', merchantWallet(), 'FEE_INCOME']);
      await S.txn('customer01', 'MERCHANT_PAY', merchantWallet(), 2500, MW.pin);
      S.status(200);
      S.txnStatus('COMPLETED');
      await S.changedBy({ '01711000001': -2500, [merchantWallet()]: 2500, 'FEE_INCOME': 0 });
      const notified = await MW.poll(() => soapCalls(MW, 'NotifyPayment'), l => l.length >= 2, 20000, 500);
      S.check('the failed notify was sent again', notified.length >= 2, JSON.stringify(notified).slice(0, 400));
      await S.appShows('COMPLETED');
    },
  },
];

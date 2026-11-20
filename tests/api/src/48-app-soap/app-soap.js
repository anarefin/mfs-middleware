// Ticket 108 (FRS CH-APP-01). SOAP Preview and Confirm of a 10 BDT P2P, then the same Idempotency-Key
// on the REST confirm. A client SOAP fault is HTTP 400 (SOAP sender). The WSDL is on the docs route.
export const helpers = [
  function soapEnvelope(header, body) {
    const ns = 'urn:mfs:mw:app:soap:v1';
    return '<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:app="' + ns + '">'
      + '<soapenv:Header>' + header + '</soapenv:Header><soapenv:Body>' + body + '</soapenv:Body></soapenv:Envelope>';
  },
  function xmlText(xml, local) {
    const marker = xml.indexOf(':' + local + '>');
    const start = marker < 0 ? xml.indexOf('<' + local + '>') : xml.lastIndexOf('<', marker);
    const from = xml.indexOf('>', start) + 1;
    return xml.slice(from, xml.indexOf('</', from)).trim();
  },
];

export default [
  {
    name: '[p2p] a SOAP preview and confirm complete a P2P and debit the previewed fee once',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE_INCOME']);
      const headers = await MW.app.headers('customer01');
      const pin = await MW.app.request('P2P', '01711000002', 1000, MW.pin);
      const preview = await MW.http('POST', '/app/ws/v1', {
        headers,
        contentType: 'text/xml; charset=UTF-8',
        raw: soapEnvelope('', '<app:PreviewRequest><app:group>send-money</app:group>'
          + '<app:creditParty><app:id>01711000002</app:id></app:creditParty>'
          + '<app:amount><app:valueMinor>1000</app:valueMinor><app:currency>BDT</app:currency></app:amount>'
          + '</app:PreviewRequest>'),
      });
      S.eq('preview HTTP 200', preview.status, 200, String(preview));
      S.eq('preview fee 500', xmlText(preview.raw, 'fee').indexOf('500') >= 0, true, String(preview));
      const previewId = xmlText(preview.raw, 'previewId');
      const key = 'soap-' + S.token;
      const confirm = await MW.http('POST', '/app/ws/v1', {
        headers,
        contentType: 'text/xml; charset=UTF-8',
        raw: soapEnvelope('<app:Idempotency-Key>' + key + '</app:Idempotency-Key>',
          '<app:ConfirmRequest><app:group>send-money</app:group><app:previewId>' + previewId + '</app:previewId>'
          + '<app:creditParty><app:id>01711000002</app:id></app:creditParty>'
          + '<app:amount><app:valueMinor>1000</app:valueMinor><app:currency>BDT</app:currency></app:amount>'
          + '<app:encryptedPin><app:keyId>' + pin.encryptedPin.keyId + '</app:keyId>'
          + '<app:ciphertext>' + pin.encryptedPin.ciphertext + '</app:ciphertext></app:encryptedPin>'
          + '</app:ConfirmRequest>'),
      });
      S.eq('confirm HTTP 200', confirm.status, 200, String(confirm));
      S.eq('completed', xmlText(confirm.raw, 'status'), 'COMPLETED', String(confirm));
      const txnId = xmlText(confirm.raw, 'txnId');
      const status = await MW.poll(async () => MW.http('POST', '/app/ws/v1', {
        headers,
        contentType: 'text/xml; charset=UTF-8',
        raw: soapEnvelope('', '<app:GetTransactionStatusRequest><app:txnId>' + txnId + '</app:txnId>'
          + '</app:GetTransactionStatusRequest>'),
      }), r => r.status === 200 && xmlText(r.raw, 'status') === 'COMPLETED', 8000, 200);
      S.eq('status HTTP 200', status.status, 200, String(status));
      S.eq('status completed', xmlText(status.raw, 'status'), 'COMPLETED', String(status));
      await S.changedBy({ '01711000001': -1500, '01711000002': 1000, 'FEE_INCOME': 435 });
    },
  },
  {
    name: '[p2p] a SOAP confirm and a REST confirm with the same idempotency key move the money once',
    run: async (MW, S) => {
      await S.note(['01711000001', '01711000002', 'FEE_INCOME']);
      const headers = await MW.app.headers('customer01');
      const pin = await MW.app.request('P2P', '01711000002', 1000, MW.pin);
      const preview = await MW.http('POST', '/app/ws/v1', {
        headers,
        contentType: 'text/xml; charset=UTF-8',
        raw: soapEnvelope('', '<app:PreviewRequest><app:group>send-money</app:group>'
          + '<app:creditParty><app:id>01711000002</app:id></app:creditParty>'
          + '<app:amount><app:valueMinor>1000</app:valueMinor><app:currency>BDT</app:currency></app:amount>'
          + '</app:PreviewRequest>'),
      });
      S.eq('preview HTTP 200', preview.status, 200, String(preview));
      const previewId = xmlText(preview.raw, 'previewId');
      const key = 'soap-rest-' + S.token;
      const body = '<app:ConfirmRequest><app:group>send-money</app:group><app:previewId>' + previewId
        + '</app:previewId><app:creditParty><app:id>01711000002</app:id></app:creditParty>'
        + '<app:amount><app:valueMinor>1000</app:valueMinor><app:currency>BDT</app:currency></app:amount>'
        + '<app:encryptedPin><app:keyId>' + pin.encryptedPin.keyId + '</app:keyId>'
        + '<app:ciphertext>' + pin.encryptedPin.ciphertext + '</app:ciphertext></app:encryptedPin>'
        + '</app:ConfirmRequest>';
      const soap = await MW.http('POST', '/app/ws/v1', {
        headers, contentType: 'text/xml; charset=UTF-8',
        raw: soapEnvelope('<app:Idempotency-Key>' + key + '</app:Idempotency-Key>', body),
      });
      S.eq('SOAP confirm HTTP 200', soap.status, 200, String(soap));
      const txnId = xmlText(soap.raw, 'txnId');
      const rest = S.record(await MW.app.confirm('customer01', 'send-money', key, {
        previewId,
        creditParty: { id: '01711000002' },
        amount: { valueMinor: 1000, currency: 'BDT' },
        encryptedPin: pin.encryptedPin,
      }));
      S.eq('REST confirm HTTP 200', rest.status, 200, String(rest));
      S.eq('one txnId', rest.body && rest.body.txnId, txnId, String(rest));
      await S.changedBy({ '01711000001': -1500, '01711000002': 1000, 'FEE_INCOME': 435 });
    },
  },
  {
    name: '[p2p] a SOAP request that is not the schema is MW-VAL-001 and does not echo the MSISDN',
    run: async (MW, S) => {
      const headers = await MW.app.headers('customer01');
      const fault = await MW.http('POST', '/app/ws/v1', {
        headers,
        contentType: 'text/xml; charset=UTF-8',
        raw: soapEnvelope('', '<app:PreviewRequest><app:group>send-money</app:group>'
          + '<app:creditParty><app:id>01711000002</app:id></app:creditParty></app:PreviewRequest>'),
      });
      S.eq('client fault HTTP 400', fault.status, 400, String(fault));
      S.check('MW-VAL-001', fault.raw.indexOf('MW-VAL-001') >= 0, String(fault));
      S.check('the MSISDN is not in the fault', fault.raw.indexOf('01711000002') < 0, String(fault));
    },
  },
  {
    name: '[docs] the app SOAP WSDL is listed on the dev docs page',
    run: async (MW, S) => {
      const page = await MW.http('GET', '/docs/');
      S.eq('docs page HTTP 200', page.status, 200, String(page));
      S.check('the page links the WSDL', page.raw.indexOf('/docs/mw-app-gateway/app/ws/v1/app.wsdl') >= 0, String(page));
      const wsdl = await MW.http('GET', '/docs/mw-app-gateway/app/ws/v1/app.wsdl');
      S.eq('WSDL HTTP 200', wsdl.status, 200, String(wsdl));
      S.check('Preview operation', wsdl.raw.indexOf('Preview') >= 0, String(wsdl));
      S.check('Confirm operation', wsdl.raw.indexOf('Confirm') >= 0, String(wsdl));
    },
  },
];

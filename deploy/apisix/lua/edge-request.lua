-- Edge request hygiene (spec 8.3, 21.2): the _meta.pre_function of request-id in global rule
-- edge, so it runs on every route right before request-id reads the header (APISIX allows a
-- plugin only once across global rules, and the rule's serverless slots hold the error
-- envelope). A pre_function runs in each phase of its plugin (request-id: rewrite and
-- header_filter); it acts only in the rewrite phase, which APISIX runs inside nginx's access
-- phase (ngx.get_phase() == "access"):
--   1. strips every inbound X-Mw-* header: internal headers are set by the middleware only,
--      so a client can never forge one;
--   2. drops an X-Correlation-Id that is repeated or not 1..128 of [A-Za-z0-9._:-] (the
--      contract pattern, same rule as the services' CorrelationIdFilter), so request-id
--      generates a fresh one and the edge and the services log the same ID.
local core = require("apisix.core")

return function(conf, ctx)
  if ngx.get_phase() ~= "access" then
    return
  end
  for name in pairs(ngx.req.get_headers(0, true)) do
    if string.lower(string.sub(name, 1, 5)) == "x-mw-" then
      core.request.set_header(ctx, name, nil)
    end
  end
  local cid = ngx.req.get_headers(0)["x-correlation-id"]
  if cid ~= nil and (type(cid) ~= "string" or #cid > 128
      or not ngx.re.find(cid, [[^[A-Za-z0-9._:-]+$]], "jo")) then
    core.request.set_header(ctx, "X-Correlation-Id", nil)
  end
end

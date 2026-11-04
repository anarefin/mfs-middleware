-- Device request signatures (spec 9.1, ticket 29) cover hex(SHA-256(body)) of the bytes the app
-- sent. request-validation (priority 2500, later in the access phase) decodes a JSON body and
-- forwards it re-encoded, so mw-app-gateway cannot hash what the device signed. This function
-- (serverless-pre-function, priority 10000, access phase: before request-validation) hashes the
-- client's bytes and forwards the hash as X-Mw-Body-Sha256; mw-app-gateway verifies the signature
-- over it (security/SignedRequests). The edge-request pre_function (rewrite phase) has already
-- removed every client X-Mw-* header, so only this function can set it. Requests without a body
-- (GET) get no header; the gateway then hashes the (empty) body it received.
local core = require("apisix.core")
local resty_sha256 = require("resty.sha256")
local to_hex = require("resty.string").to_hex

return function(conf, ctx)
  if core.request.get_method() ~= "POST" then
    return
  end
  local body, err = core.request.get_body()
  if err then
    core.log.error("failed to read the body for X-Mw-Body-Sha256: ", err)
    return 400, "request body could not be read" -- the edge error envelope wraps it
  end
  local sha = resty_sha256:new()
  sha:update(body or "")
  core.request.set_header(ctx, "X-Mw-Body-Sha256", to_hex(sha:final()))
end

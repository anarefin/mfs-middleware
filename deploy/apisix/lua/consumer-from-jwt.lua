-- Per-consumer key for limit-count (spec 8.3), access phase, on routes authenticated by
-- openid-connect. openid-connect (rewrite phase) has already verified the bearer token, so
-- its `sub` claim (the Keycloak user or service-account id, not personal data) is trusted.
-- Sets ctx.var.mw_consumer, which limit-count (key_type var, key mw_consumer) reads; without
-- it limit-count falls back to the client address.
local core = require("apisix.core")
local b64 = require("ngx.base64")

return function(conf, ctx)
  local auth = core.request.header(ctx, "Authorization")
  if type(auth) ~= "string" then
    return
  end
  local payload = string.match(auth, "^[Bb]earer%s+[^.]+%.([^.]+)%.")
  if not payload then
    return
  end
  local claims = core.json.decode(b64.decode_base64url(payload) or "")
  if type(claims) == "table" and type(claims.sub) == "string" and claims.sub ~= "" then
    ctx.var.mw_consumer = "sub:" .. claims.sub
  end
end

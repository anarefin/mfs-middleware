-- Per-client key for limit-count / limit-req on an OAUTH2_CC route (ticket 100b).
-- openid-connect (rewrite) has already verified the partners-realm bearer token, so its
-- azp claim (the Keycloak client id, not a person) is trusted. Sets ctx.var.mw_client_id.
-- Only a client registered for this route passes; the registry rewrite of this function
-- (ApisixConsumerSync) is the allow-list. This file is the bootstrap for partner-sim.
local core = require("apisix.core")
local b64 = require("ngx.base64")
local allowed = { ["partner-sim"] = true }

return function(conf, ctx)
  local auth = core.request.header(ctx, "Authorization")
  if type(auth) ~= "string" then
    return 401
  end
  local payload = string.match(auth, "^[Bb]earer%s+[^.]+%.([^.]+)%.")
  if not payload then
    return 401
  end
  local claims = core.json.decode(b64.decode_base64url(payload) or "")
  local id = nil
  if type(claims) == "table" then
    id = claims.azp or claims.client_id
  end
  if type(id) ~= "string" or allowed[id] ~= true then
    return 403
  end
  ctx.var.mw_client_id = id
end

-- Per-wallet key for limit-count on route auth-v1 (mw-auth's login API, ticket 69; spec 8.3, 18.7),
-- access phase. These requests carry no verified token (they prove the wallet themselves with the
-- device signature, OTP and PIN), so the counter is keyed on the wallet the request names: the
-- msisdn of the JSON body (OTP, binding, login), canonicalised 8801X... / +8801X... to 01X... and
-- hashed: ctx.var.mw_auth_key = "msisdn:<sha256 hex>", so the Redis key never holds the MSISDN.
-- Without one (refresh, logout, first PIN) the key is "sub:<sub>" of a bearer token when there is
-- one (not verified here: mw-auth verifies it; a forged sub only moves the caller to another
-- counter, and the per-address limit-req still holds), else unset and limit-count counts per source
-- address. The body is read, never changed (the device signs it).
local core = require("apisix.core")
local b64 = require("ngx.base64")
local resty_sha256 = require("resty.sha256")
local to_hex = require("resty.string").to_hex

return function(conf, ctx)
  local body = core.request.get_body()
  if type(body) == "string" and body ~= "" then
    local doc = core.json.decode(body)
    if type(doc) == "table" and type(doc.msisdn) == "string" then
      local msisdn = string.gsub(doc.msisdn, "^%+", "")
      if string.sub(msisdn, 1, 3) == "880" then
        msisdn = string.sub(msisdn, 3)
      end
      local sha = resty_sha256:new()
      sha:update(msisdn)
      ctx.var.mw_auth_key = "msisdn:" .. to_hex(sha:final())
      return
    end
  end
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
    ctx.var.mw_auth_key = "sub:" .. claims.sub
  end
end

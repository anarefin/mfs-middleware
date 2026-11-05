-- Per-MSISDN key for limit-count on the MNO USSD routes ussd-http-<mno> (spec 8.3, M3 gate),
-- access phase, after basic-auth and consumer-restriction admitted the MNO. Reads the msisdn of
-- the JSON step (contract api/mw-ussd/ussd-http.v1), canonicalises 8801X... / +8801X... to
-- 01X..., and sets ctx.var.mw_ussd_msisdn = "<mno>:<sha256 hex of the canonical number>", which
-- limit-count (key_type var, key mw_ussd_msisdn) reads: the Valkey counter key never holds the
-- MSISDN. Without a readable msisdn the key stays unset and limit-count counts per source
-- address; mw-ussd refuses such a step with 400 anyway. The body is read, never changed.
local core = require("apisix.core")
local resty_sha256 = require("resty.sha256")
local to_hex = require("resty.string").to_hex

return function(conf, ctx)
  local mno = string.match(ctx.var.uri or "", "^/ussd/([a-z0-9]+)$")
  if not mno then
    return
  end
  local body = core.request.get_body()
  if type(body) ~= "string" or body == "" then
    return
  end
  local step = core.json.decode(body)
  if type(step) ~= "table" or type(step.msisdn) ~= "string" then
    return
  end
  local msisdn = string.gsub(step.msisdn, "^%+", "")
  if string.sub(msisdn, 1, 3) == "880" then
    msisdn = string.sub(msisdn, 3)
  end
  local sha = resty_sha256:new()
  sha:update(msisdn)
  ctx.var.mw_ussd_msisdn = mno .. ":" .. to_hex(sha:final())
end

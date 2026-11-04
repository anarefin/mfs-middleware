-- Edge error envelope, part 2 of 2 (spec 5.3), global rule edge, body_filter phase.
-- Replaces the body of a response marked by edge-error-headers.lua with the envelope. For a
-- 400 the original text (request-validation's or openid-connect's reason, never a request
-- value: deploy/apisix/schemas carry no pattern or format) becomes details[0].issue.
local cjson = require("cjson.safe")

return function(conf, ctx)
  local e = ctx.mw_edge_error
  if not e then
    return
  end
  local chunk, eof = ngx.arg[1], ngx.arg[2]
  if e.status == 400 and type(chunk) == "string" and #e.original < 1024 then
    e.original = e.original .. chunk
  end
  if not eof then
    ngx.arg[1] = ""
    return
  end
  local details = setmetatable({}, cjson.array_mt)
  if e.status == 400 then
    local text = e.original
    local decoded = cjson.decode(text)
    if type(decoded) == "table" and type(decoded.error_msg) == "string" then
      text = decoded.error_msg
    end
    text = string.gsub(text, "%s+$", "")
    if text ~= "" and string.sub(text, 1, 1) ~= "<" then
      details[1] = { issue = string.sub(text, 1, 300) }
    end
  end
  ngx.arg[1] = cjson.encode({
    code = e.code,
    message = e.message,
    correlationId = e.correlationId,
    retryable = e.retryable,
    details = details,
  })
  ctx.mw_edge_error = nil
end

-- Edge error envelope, part 1 of 2 (spec 5.3), global rule edge, header_filter phase.
-- A 4xx/5xx the edge produced itself (plugin rejection, no route, upstream unreachable or
-- timed out) gets the envelope { code, message, correlationId, retryable, details[] } instead
-- of APISIX's HTML or {"error_msg"} body. An upstream's own JSON answer is left untouched: the
-- services already answer with the envelope. The body is written by edge-error-body.lua.
local CODES = {
  [400] = { "MW-VAL-001", "Request is not valid", false },
  [401] = { "MW-AUTH-001", "Missing, expired or invalid bearer token", false },
  [403] = { "MW-AUTH-002", "Not allowed", false },
  [404] = { "MW-VAL-003", "No such endpoint", false },
  [405] = { "MW-VAL-004", "Method not allowed", false },
  [413] = { "MW-VAL-BODY_TOO_LARGE", "Request body too large", false },
  [429] = { "MW-SYS-RATE_LIMITED", "Too many requests, retry later", true },
  [502] = { "MW-SYS-UPSTREAM_UNAVAILABLE", "Service temporarily unavailable, retry later", true },
  [503] = { "MW-SYS-UPSTREAM_UNAVAILABLE", "Service temporarily unavailable, retry later", true },
  [504] = { "MW-TMO-UPSTREAM_TIMEOUT", "No answer in time, the outcome is unknown: check the status before retrying", true },
}

return function(conf, ctx)
  local status = ngx.status
  if status < 400 then
    return
  end
  local upstream = ngx.var.upstream_status
  local ctype = ngx.header["Content-Type"]
  if upstream and upstream ~= "" and type(ctype) == "string"
      and string.find(string.lower(ctype), "json", 1, true) then
    return
  end
  local e = CODES[status]
      or (status >= 500 and { "MW-SYS-001", "Internal error", true })
      or { "MW-VAL-002", "Request could not be processed", false }
  local cid = ngx.req.get_headers(0)["x-correlation-id"]
  if type(cid) ~= "string" or cid == "" then
    cid = ngx.var.request_id
  end
  -- envelope fields; details are filled from the original body (400 only) by the body filter
  ctx.mw_edge_error = { status = status, code = e[1], message = e[2], retryable = e[3],
                        correlationId = cid, original = "" }
  ngx.header["Content-Type"] = "application/json"
  ngx.header["Content-Length"] = nil
  ngx.header["Content-Encoding"] = nil
  ngx.header["X-Correlation-Id"] = cid
end

-- Rate-limiter degradation alert (ticket 50), global rule edge, header_filter phase.
-- limit-count stays fail-open (allow_degradation: true): when Valkey is down or too slow it lets
-- the request through unlimited and sets none of its quota headers. A request that reached the
-- upstream on a route with limit-count (and show_limit_quota_header) but carries no
-- X-RateLimit-Limit was therefore not counted: log the stable error line
-- "mw.edge.rate_limit_degraded" with the route id and status (never headers or bodies), which
-- scripts/smoke.sh and the alerting grep for. limit-count's own 429 and every request stopped before
-- the limiter (401, 400, 413...) never reach the upstream, so they never match.
local core = require("apisix.core")

return function(conf, ctx)
  local upstream = ngx.var.upstream_status
  if not upstream or upstream == "" then
    return
  end
  local route = ctx.matched_route and ctx.matched_route.value
  local limit = route and route.plugins and route.plugins["limit-count"]
  if type(limit) ~= "table" or not limit.show_limit_quota_header then
    return
  end
  if ngx.header["X-RateLimit-Limit"] ~= nil then
    return
  end
  core.log.error("mw.edge.rate_limit_degraded route=", route.id or "?",
                 " status=", ngx.status, " (limit-count failed open: Valkey unreachable or slow)")
end

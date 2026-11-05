-- Edge security headers (spec 18.6, ticket 46 ZAP baseline), global rule edge, header_filter
-- phase, after edge-error-headers.lua. Every HTTPS response gets HSTS and nosniff; a response
-- the edge produced itself (ctx.mw_edge_error: no route, plugin rejection, upstream down) also
-- gets the headers the services' SecurityHeadersFilter sends, as it never reached a service.
-- An upstream's own value is kept.
local function default(name, value)
  if ngx.header[name] == nil then
    ngx.header[name] = value
  end
end

return function(conf, ctx)
  if ngx.var.scheme == "https" then
    default("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
  end
  default("X-Content-Type-Options", "nosniff")
  if ctx.mw_edge_error then
    ngx.header["Cache-Control"] = "no-store"
    ngx.header["X-Frame-Options"] = "DENY"
    ngx.header["Referrer-Policy"] = "no-referrer"
    ngx.header["Content-Security-Policy"] = "default-src 'none'; frame-ancestors 'none'"
  end
end

-- Realm ops token endpoint on the public browser route auth-ops-browser (security review,
-- sec-ops), access phase. The console's login is Authorization Code + PKCE: the browser never
-- posts a password to the token endpoint (mw-admin-ui exchanges the code in the stack, and
-- Keycloak's own account console uses code + refresh). So on this route a POST to
-- /realms/ops/protocol/openid-connect/token must be a form with exactly one grant_type,
-- authorization_code or refresh_token: anything else (password, client_credentials, token
-- exchange, device, CIBA, ...) is refused with 403 before Keycloak sees it. The e2e password
-- grant uses the test-only route auth-ops-token instead. Sub-endpoints under .../token/
-- (introspection) are not public: 404. The path is compared as Keycloak would route it:
-- nginx has already decoded and normalised it; matrix parameters (";x") and case are folded
-- here too. The body is read, never changed.
local core = require("apisix.core")

local ALLOWED = { authorization_code = true, refresh_token = true }
local FORM = "application/x-www-form-urlencoded"

return function(conf, ctx)
  local path = string.lower(ctx.var.uri or "")
  path = string.gsub(path, ";[^/]*", "")
  path = string.gsub(path, "/+", "/")
  if not string.find(path, "^/realms/ops/protocol/openid%-connect/token") then
    return
  end
  if path ~= "/realms/ops/protocol/openid-connect/token"
      and path ~= "/realms/ops/protocol/openid-connect/token/" then
    return core.response.exit(404)
  end
  if ngx.req.get_method() ~= "POST" then
    return core.response.exit(405)
  end
  local ctype = string.lower(core.request.header(ctx, "Content-Type") or "")
  if string.sub(ctype, 1, #FORM) ~= FORM then
    return core.response.exit(400)
  end
  local args = core.request.get_post_args(ctx)
  local grant = args and args.grant_type
  if type(grant) ~= "string" or not ALLOWED[grant] then
    return core.response.exit(403)
  end
end

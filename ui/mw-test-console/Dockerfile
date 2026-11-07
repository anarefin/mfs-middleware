# mw-test-console (dev/test only, ADR D17): Next.js standalone server, non-root, no dev dependencies
# or test doubles in the image. Build: docker build -t mw-test-console:dev .
FROM node:24-alpine AS build
WORKDIR /app
RUN corepack enable
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY next.config.ts tsconfig.json ./
COPY src ./src
ENV NEXT_TELEMETRY_DISABLED=1
RUN pnpm exec next build

FROM node:24-alpine
# Security pass (ticket 46): the server needs node only. npm, npx and corepack (their bundled
# undici and brace-expansion carry HIGH CVEs) are removed; Alpine fixes newer than the base.
RUN apk upgrade --no-cache \
 && rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack \
      /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0
COPY --from=build --chown=10001:10001 /app/.next/standalone ./
COPY --from=build --chown=10001:10001 /app/.next/static ./.next/static
USER 10001:10001
EXPOSE 3000
HEALTHCHECK --interval=5s --timeout=3s --start-period=20s --retries=3 \
  CMD wget -qO- http://127.0.0.1:3000/tester/api/health | grep -q UP || exit 1
CMD ["node", "server.js"]

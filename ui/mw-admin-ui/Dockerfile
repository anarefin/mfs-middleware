# mw-admin-ui (spec 15.1): Next.js standalone server, non-root, no dev dependencies or test
# doubles in the image. Build: docker build -t mw-admin-ui:dev .
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
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0
COPY --from=build --chown=10001:10001 /app/.next/standalone ./
COPY --from=build --chown=10001:10001 /app/.next/static ./.next/static
USER 10001:10001
EXPOSE 3000
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=3 \
  CMD wget -qO- http://127.0.0.1:3000/console/api/health | grep -q UP || exit 1
CMD ["node", "server.js"]

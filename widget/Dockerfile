# syntax=docker/dockerfile:1
# ---------------------------------------------------------------------------
# FlexPop web (Vite build → nginx static). Build context = ./widget
# Build: docker build -t flexpop-web ./widget
# Run:   docker run -p 8088:80 -e ENGINE_PUBLIC_URL=https://api.example.com flexpop-web
# ---------------------------------------------------------------------------

# ----- build stage -----
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build          # → /app/dist (index.html, demo.html, assets, flexpop.js, icons)

# ----- runtime stage -----
FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
# Injects window.__ENGINE from $ENGINE_PUBLIC_URL at container start.
COPY docker-entrypoint.sh /docker-entrypoint.d/40-flexpop-config.sh
RUN chmod +x /docker-entrypoint.d/40-flexpop-config.sh
EXPOSE 80

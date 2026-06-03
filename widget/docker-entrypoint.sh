#!/bin/sh
# Runs inside nginx:alpine's startup (/docker-entrypoint.d) before nginx boots.
# Writes /config.js so the demo page learns the engine's public URL at runtime
# (the static build can't know it). demo.html reads window.__ENGINE.
set -e
CONFIG=/usr/share/nginx/html/config.js
if [ -n "$ENGINE_PUBLIC_URL" ]; then
  printf "window.__ENGINE=%s;\n" "\"$ENGINE_PUBLIC_URL\"" > "$CONFIG"
  echo "flexpop-web: ENGINE_PUBLIC_URL=$ENGINE_PUBLIC_URL → /config.js"
else
  printf "// ENGINE_PUBLIC_URL not set; demo falls back to <host>:8080\n" > "$CONFIG"
  echo "flexpop-web: ENGINE_PUBLIC_URL unset; using host:8080 fallback"
fi

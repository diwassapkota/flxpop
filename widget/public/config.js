// Runtime config. In dev this is a no-op (the demo falls back to
// `<host>:8080`). In the container, the entrypoint overwrites this file with
// `window.__ENGINE = "<ENGINE_PUBLIC_URL>";` so the deployed engine URL is
// injected without rebuilding the static bundle.

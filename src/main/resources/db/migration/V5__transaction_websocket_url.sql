-- Server-side Fonepay payment socket: persist the per-transaction WebSocket URL
-- (returned by generate-intent-qr) so the engine can connect to it itself and
-- settle the transaction from the `paymentSuccess` frame — and re-attach after a
-- restart. This is the confirmation path for Fonepay: the Intent API has no
-- webhook, the status API is 409-blocked for this merchant, and the in-browser
-- socket can't survive a mobile tab reload.
ALTER TABLE transaction ADD COLUMN websocket_url VARCHAR(512);

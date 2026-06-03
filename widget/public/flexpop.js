// FlexPop loader — tiny script merchants embed in their checkout page.
//
// PRODUCTION embed (always use Subresource Integrity — protects you against
// a compromised CDN serving a tampered loader):
//
//   <script
//     src="https://cdn.flexpop.io/widget/v0.1.0/flexpop.js"
//     integrity="sha384-REPLACE_WITH_PUBLISHED_HASH"
//     crossorigin="anonymous"></script>
//   <div id="my-checkout"></div>
//   <script>
//     FlexPop.mount({
//       container: '#my-checkout',
//       engineBaseUrl: 'https://api.flexpop.io',
//       publishableKey: 'pk_live_...',
//       session: { /* SessionResponse from your server-side POST /v1/sessions */ },
//       onSettled: (evt) => { /* fulfil order */ },
//       onFailed:  (evt) => { /* show retry */ },
//     });
//   </script>
//
// The integrity hash is published per release at https://docs.flexpop.io/widget/releases.
// Pin a specific version path and SRI hash; never use a "latest" URL in production.
//
// In dev (SRI not required since you're loading from a same-origin dev server):
//   FlexPop.mount({
//     container: '#my-checkout',
//     widgetOrigin:   'http://localhost:5173',
//     engineBaseUrl:  'http://localhost:8080',
//     publishableKey: 'pk_dev_local_FLEXPOPPUBLICKEY1234567890',
//     session: ...,
//   });

(function (global) {
  'use strict';

  var DEFAULT_WIDGET_ORIGIN = 'https://widget.flexpop.io';

  function mount(opts) {
    if (!opts) throw new Error('FlexPop.mount: opts required');
    var widgetOrigin = opts.widgetOrigin || DEFAULT_WIDGET_ORIGIN;

    var container = typeof opts.container === 'string'
      ? document.querySelector(opts.container)
      : opts.container;
    if (!container) throw new Error('FlexPop.mount: container not found: ' + opts.container);

    var iframe = document.createElement('iframe');
    iframe.title = 'FlexPop Checkout';
    iframe.src = widgetOrigin + '/';
    iframe.allow = 'payment';
    // min-height floors the frame so a too-early/zero resize message can never
    // collapse it to a blank sliver (seen on some mobile browsers).
    iframe.style.cssText = 'border:0;width:100%;height:440px;min-height:360px;display:block;background:transparent;transition:height .15s ease;';
    container.appendChild(iframe);

    var booted = false;
    function postBoot() {
      if (booted || !iframe.contentWindow) return;
      booted = true;
      iframe.contentWindow.postMessage({
        type: 'flexpop:boot',
        engineBaseUrl:  opts.engineBaseUrl,
        publishableKey: opts.publishableKey,
        session:        opts.session,
        gateway:        opts.gateway,  // optional: deep-link straight to one gateway
      }, widgetOrigin);
    }

    function onMessage(e) {
      if (e.source !== iframe.contentWindow) return;
      if (widgetOrigin !== '*' && e.origin !== widgetOrigin) return;
      var msg = e.data || {};
      switch (msg.type) {
        case 'flexpop:resize':
          if (msg.height && msg.height > 0) iframe.style.height = msg.height + 'px';
          break;
        case 'flexpop:ready':    postBoot(); break;
        case 'flexpop:settled':  opts.onSettled  && opts.onSettled(msg); break;
        case 'flexpop:failed':   opts.onFailed   && opts.onFailed(msg);  break;
        case 'flexpop:expired':  opts.onExpired  && opts.onExpired(msg); break;
        case 'flexpop:initiated':opts.onInitiated && opts.onInitiated(msg); break;
      }
    }

    window.addEventListener('message', onMessage);
    // Primary: boot when the widget posts 'flexpop:ready' (its message listener
    // is guaranteed attached by then). Fallback: a delayed boot on 'load' in
    // case the ready handshake is missed — delayed so it can't fire before the
    // widget's listener exists (the race that left the frame blank on mobile).
    iframe.addEventListener('load', function () { setTimeout(postBoot, 400); });

    return {
      destroy: function () {
        window.removeEventListener('message', onMessage);
        if (iframe.parentNode) iframe.parentNode.removeChild(iframe);
      },
    };
  }

  global.FlexPop = { mount: mount };
})(window);

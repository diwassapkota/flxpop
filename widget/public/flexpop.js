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
    iframe.style.cssText = 'border:0;width:100%;min-height:560px;display:block;background:transparent;';
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
      }, widgetOrigin);
    }

    function onMessage(e) {
      if (e.source !== iframe.contentWindow) return;
      if (widgetOrigin !== '*' && e.origin !== widgetOrigin) return;
      var msg = e.data || {};
      switch (msg.type) {
        case 'flexpop:ready':    postBoot(); break;
        case 'flexpop:settled':  opts.onSettled  && opts.onSettled(msg); break;
        case 'flexpop:failed':   opts.onFailed   && opts.onFailed(msg);  break;
        case 'flexpop:expired':  opts.onExpired  && opts.onExpired(msg); break;
        case 'flexpop:initiated':opts.onInitiated && opts.onInitiated(msg); break;
      }
    }

    window.addEventListener('message', onMessage);
    iframe.addEventListener('load', postBoot);

    return {
      destroy: function () {
        window.removeEventListener('message', onMessage);
        if (iframe.parentNode) iframe.parentNode.removeChild(iframe);
      },
    };
  }

  global.FlexPop = { mount: mount };
})(window);

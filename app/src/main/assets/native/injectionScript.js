(() => {
    const mainScript = document.currentScript;
    const scripts = [
        '/native/nativeshell.js',
        '/native/EventEmitter.js',
        mainScript.src.concat('?deferred=true&ts=', Date.now())
    ];

    function loadWebClient() {
        for (const script of scripts) {
            const scriptElement = document.createElement('script');
            scriptElement.src = script;
            scriptElement.charset = 'utf-8';
            // Dynamically inserted scripts need async=false to preserve order.
            scriptElement.async = false;
            document.body.appendChild(scriptElement);
        }
    }

    // Load VideoElementProxy first (needs to intercept video element creation early)
    if (window.VideoProxyBridge && window.VideoProxyBridge.isEnabled()) {
        const proxyScript = document.createElement('script');
        proxyScript.src = '/native/VideoElementProxy.js';
        proxyScript.charset = 'utf-8';
        proxyScript.onload = loadWebClient;
        proxyScript.onerror = loadWebClient;
        document.head.appendChild(proxyScript);
    } else {
        loadWebClient();
    }
    mainScript.remove();
})();

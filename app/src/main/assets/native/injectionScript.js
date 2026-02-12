(() => {
    // Load VideoElementProxy first (needs to intercept video element creation early)
    if (window.VideoProxyBridge && window.VideoProxyBridge.isEnabled()) {
        const proxyScript = document.createElement('script');
        proxyScript.src = '/native/VideoElementProxy.js';
        proxyScript.charset = 'utf-8';
        document.head.appendChild(proxyScript);
    }

    const scripts = [
        '/native/nativeshell.js',
        '/native/EventEmitter.js',
        document.currentScript.src.concat('?deferred=true&ts=', Date.now())
    ];
    for (const script of scripts) {
        const scriptElement = document.createElement('script');
        scriptElement.src = script;
        scriptElement.charset = 'utf-8';
        scriptElement.setAttribute('defer', '');
        document.body.appendChild(scriptElement);
    }
    document.currentScript.remove();
})();

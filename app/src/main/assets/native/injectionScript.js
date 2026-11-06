(() => {
    const mainScript = document.currentScript;
    const scripts = [
        '/native/nativeshell.js',
        '/native/EventEmitter.js',
        mainScript.src.concat('?deferred=true')
    ];

    // Give the video element a safe content box so native proxy bounds also stay below the status bar.
    const playbackInsets = document.createElement('style');
    playbackInsets.textContent = `
        @media (orientation: portrait) {
            /* Episode mode uses this height to align the video, controls and episode panel. */
            html body.episodeModeActive {
                --episode-video-height: calc(min(56.25vw, 55vh) + env(safe-area-inset-top, 0px));
            }

            .videoPlayerContainer {
                box-sizing: border-box;
                padding-top: env(safe-area-inset-top, 0px);
            }
        }
    `;
    document.head.appendChild(playbackInsets);

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

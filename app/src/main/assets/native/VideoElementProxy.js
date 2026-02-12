/**
 * Video Element Proxy for Jellyfin Android
 * 
 * This script intercepts HTML video element operations and delegates
 * actual video rendering to the native ExoPlayer through the VideoProxyBridge.
 */
(function() {
    'use strict';

    // Check if proxy is enabled
    const bridge = window.VideoProxyBridge;
    if (!bridge || !bridge.isEnabled()) {
        console.log('[VideoProxy] Video proxy is disabled');
        return;
    }

    console.log('[VideoProxy] Initializing video element proxy');

    // Store for proxied video elements
    const proxiedVideos = new Map();
    let videoIdCounter = 0;

    // Generate unique video ID
    function generateVideoId() {
        return 'video_proxy_' + (++videoIdCounter);
    }

    // Debounce helper
    function debounce(func, wait) {
        let timeout;
        return function executedFunction(...args) {
            const later = () => {
                clearTimeout(timeout);
                func(...args);
            };
            clearTimeout(timeout);
            timeout = setTimeout(later, wait);
        };
    }

    /**
     * Proxy AudioTrack - mimics the HTML5 AudioTrack interface.
     * Setting 'enabled' triggers native audio track switching via the bridge.
     */
    class ProxyAudioTrack {
        constructor(trackList, info) {
            this._trackList = trackList;
            this.id = String(info.index);
            this.kind = info.isDefault ? 'main' : 'alternative';
            this.label = info.label || '';
            this.language = info.language || '';
            this._enabled = info.isSelected || false;
            this._index = info.index;
            this.channelCount = info.channelCount || 0;
            this.codec = info.codec || '';
        }

        get enabled() { return this._enabled; }
        set enabled(value) {
            if (value === this._enabled) return;
            if (value) {
                // Disable all other tracks in the list
                this._trackList._tracks.forEach(t => { t._enabled = false; });
                this._enabled = true;
                bridge.setAudioTrack(this._trackList._videoId, this._index);
                this._trackList._dispatchChange();
            } else {
                // Audio must have at least one enabled track; ignore disable
            }
        }
    }

    /**
     * Proxy AudioTrackList - mimics the HTML5 AudioTrackList interface.
     * Supports indexed access, length, getTrackById, and change events.
     */
    class ProxyAudioTrackList {
        constructor(videoId) {
            this._videoId = videoId;
            this._tracks = [];
            this._listeners = { change: [], addtrack: [], removetrack: [] };
            this.onchange = null;
            this.onaddtrack = null;
            this.onremovetrack = null;

            // Return a Proxy so that indexed access (list[0], list[1]) works
            return new Proxy(this, {
                get(target, prop, receiver) {
                    if (typeof prop === 'string' && /^\d+$/.test(prop)) {
                        return target._tracks[parseInt(prop, 10)];
                    }
                    return Reflect.get(target, prop, receiver);
                }
            });
        }

        get length() { return this._tracks.length; }

        getTrackById(id) {
            return this._tracks.find(t => t.id === id) || null;
        }

        addEventListener(type, listener) {
            if (this._listeners[type]) this._listeners[type].push(listener);
        }

        removeEventListener(type, listener) {
            if (this._listeners[type]) {
                this._listeners[type] = this._listeners[type].filter(l => l !== listener);
            }
        }

        _update(trackInfos) {
            this._tracks = trackInfos.map(info => new ProxyAudioTrack(this, info));
            this._dispatchChange();
        }

        _dispatchChange() {
            const event = new Event('change');
            this._listeners.change.forEach(l => l(event));
            if (typeof this.onchange === 'function') this.onchange(event);
        }
    }

    /**
     * Proxy TextTrack - mimics the HTML5 TextTrack interface.
     * Setting 'mode' triggers native subtitle track switching via the bridge.
     */
    class ProxyTextTrack {
        constructor(trackList, info) {
            this._trackList = trackList;
            this.id = String(info.index);
            this.kind = info.isForced ? 'forced' : 'subtitles';
            this.label = info.label || '';
            this.language = info.language || '';
            this._mode = info.isSelected ? 'showing' : 'disabled';
            this._index = info.index;
            this.codec = info.codec || '';
            this.cues = null;
            this.activeCues = null;
            this.oncuechange = null;
        }

        get mode() { return this._mode; }
        set mode(value) {
            if (value === this._mode) return;
            const oldMode = this._mode;
            this._mode = value;

            if (value === 'showing' || value === 'hidden') {
                // Disable all other tracks first
                this._trackList._tracks.forEach(t => {
                    if (t !== this) t._mode = 'disabled';
                });
                bridge.setSubtitleTrack(this._trackList._videoId, this._index);
            } else if (value === 'disabled') {
                // Check if all tracks are now disabled
                const anyShowing = this._trackList._tracks.some(t => t._mode === 'showing' || t._mode === 'hidden');
                if (!anyShowing) {
                    bridge.disableSubtitleTrack(this._trackList._videoId);
                }
            }

            this._trackList._dispatchChange();
        }

        addEventListener(type, listener) { /* stub */ }
        removeEventListener(type, listener) { /* stub */ }
    }

    /**
     * Proxy TextTrackList - mimics the HTML5 TextTrackList interface.
     * Supports indexed access, length, getTrackById, and change events.
     */
    class ProxyTextTrackList {
        constructor(videoId) {
            this._videoId = videoId;
            this._tracks = [];
            this._listeners = { change: [], addtrack: [], removetrack: [] };
            this.onchange = null;
            this.onaddtrack = null;
            this.onremovetrack = null;

            return new Proxy(this, {
                get(target, prop, receiver) {
                    if (typeof prop === 'string' && /^\d+$/.test(prop)) {
                        return target._tracks[parseInt(prop, 10)];
                    }
                    return Reflect.get(target, prop, receiver);
                }
            });
        }

        get length() { return this._tracks.length; }

        getTrackById(id) {
            return this._tracks.find(t => t.id === id) || null;
        }

        addEventListener(type, listener) {
            if (this._listeners[type]) this._listeners[type].push(listener);
        }

        removeEventListener(type, listener) {
            if (this._listeners[type]) {
                this._listeners[type] = this._listeners[type].filter(l => l !== listener);
            }
        }

        _update(trackInfos) {
            this._tracks = trackInfos.map(info => new ProxyTextTrack(this, info));
            this._dispatchChange();
        }

        _dispatchChange() {
            const event = new Event('change');
            this._listeners.change.forEach(l => l(event));
            if (typeof this.onchange === 'function') this.onchange(event);
        }
    }

    /**
     * Video Proxy State - stores the state for a proxied video element
     */
    class VideoProxyState {
        constructor(videoId, originalVideo) {
            this.videoId = videoId;
            this.originalVideo = originalVideo;
            this.isProxied = false;
            
            // Whether the native ExoPlayer has been created for this video
            this._nativeCreated = false;
            
            // Playback state
            this._src = '';
            this._currentTime = 0;
            this._duration = 0;
            this._paused = true;
            this._ended = false;
            this._volume = 1.0;
            this._muted = false;
            this._playbackRate = 1.0;
            this._readyState = 0;
            this._buffered = null;

            // Track lists
            this._audioTrackList = new ProxyAudioTrackList(videoId);
            this._textTrackList = new ProxyTextTrackList(videoId);
            
            // Position tracking
            this._bounds = { x: 0, y: 0, width: 0, height: 0 };
            this._visible = true;
            this._fullscreen = false;
            
            // Saved ancestor styles for transparency restoration
            this._savedStyles = [];
            
            // Observers
            this._resizeObserver = null;
            this._intersectionObserver = null;
            this._mutationObserver = null;
            this._boundUpdatePosition = debounce(this.updatePosition.bind(this), 16);
        }

        /**
         * Ensure the native ExoPlayer is created (lazy initialization).
         * Only creates the player on first call; subsequent calls are no-ops.
         */
        ensureNativePlayer() {
            if (this._nativeCreated) return;
            this._nativeCreated = true;
            console.log(`[VideoProxy] Creating native player for ${this.videoId}`);
            bridge.onVideoElementCreated(this.videoId);
        }

        /**
         * Make the video area transparent so the native TextureView
         * beneath the WebView can show through.
         * Sets all ancestor elements' backgrounds to transparent and
         * saves original values for later restoration.
         */
        makeTransparent() {
            this._savedStyles = [];

            // Make video element transparent
            this.originalVideo.style.setProperty('opacity', '0', 'important');
            this.originalVideo.style.setProperty('background', 'transparent', 'important');
            this.originalVideo.style.setProperty('background-color', 'transparent', 'important');

            // Traverse up the DOM tree and make all ancestors transparent
            // This includes all containers up to and including <html>
            let element = this.originalVideo.parentElement;
            while (element) {
                this._savedStyles.push({
                    element: element,
                    background: element.style.getPropertyValue('background'),
                    backgroundPriority: element.style.getPropertyPriority('background'),
                    backgroundColor: element.style.getPropertyValue('background-color'),
                    backgroundColorPriority: element.style.getPropertyPriority('background-color'),
                });
                element.style.setProperty('background', 'transparent', 'important');
                element.style.setProperty('background-color', 'transparent', 'important');
                element = element.parentElement;
            }

            console.log(`[VideoProxy] Made ${this._savedStyles.length} ancestors transparent for ${this.videoId}`);
        }

        /**
         * Restore the original backgrounds of ancestor elements.
         */
        restoreTransparency() {
            // Restore video element
            this.originalVideo.style.removeProperty('opacity');
            this.originalVideo.style.removeProperty('background');
            this.originalVideo.style.removeProperty('background-color');

            // Restore ancestors
            for (const saved of this._savedStyles) {
                if (saved.background) {
                    saved.element.style.setProperty('background', saved.background, saved.backgroundPriority || '');
                } else {
                    saved.element.style.removeProperty('background');
                }
                if (saved.backgroundColor) {
                    saved.element.style.setProperty('background-color', saved.backgroundColor, saved.backgroundColorPriority || '');
                } else {
                    saved.element.style.removeProperty('background-color');
                }
            }

            if (this._savedStyles.length > 0) {
                console.log(`[VideoProxy] Restored ${this._savedStyles.length} ancestor styles for ${this.videoId}`);
            }
            this._savedStyles = [];
        }

        /**
         * Start observing the video element for position/size changes
         */
        startObserving() {
            // Resize observer for size changes
            this._resizeObserver = new ResizeObserver(() => {
                this._boundUpdatePosition();
            });
            this._resizeObserver.observe(this.originalVideo);

            // Intersection observer for visibility
            this._intersectionObserver = new IntersectionObserver((entries) => {
                for (const entry of entries) {
                    this._visible = entry.isIntersecting;
                    bridge.setVisibility(this.videoId, this._visible);
                }
            }, { threshold: [0, 0.1, 0.5, 1.0] });
            this._intersectionObserver.observe(this.originalVideo);

            // Listen for scroll events
            window.addEventListener('scroll', this._boundUpdatePosition, { passive: true });
            document.addEventListener('scroll', this._boundUpdatePosition, { passive: true, capture: true });

            // Initial position update
            this.updatePosition();
        }

        /**
         * Stop observing
         */
        stopObserving() {
            if (this._resizeObserver) {
                this._resizeObserver.disconnect();
                this._resizeObserver = null;
            }
            if (this._intersectionObserver) {
                this._intersectionObserver.disconnect();
                this._intersectionObserver = null;
            }
            window.removeEventListener('scroll', this._boundUpdatePosition);
            document.removeEventListener('scroll', this._boundUpdatePosition, { capture: true });
        }

        /**
         * Update the position of the video overlay
         */
        updatePosition() {
            const rect = this.originalVideo.getBoundingClientRect();
            const newBounds = {
                x: rect.left,
                y: rect.top,
                width: rect.width,
                height: rect.height
            };

            // Only update if bounds changed
            if (this._bounds.x !== newBounds.x ||
                this._bounds.y !== newBounds.y ||
                this._bounds.width !== newBounds.width ||
                this._bounds.height !== newBounds.height) {
                this._bounds = newBounds;
                bridge.updateBounds(this.videoId, JSON.stringify(newBounds));
            }
        }

        /**
         * Dispatch a synthetic event on the original video element
         */
        dispatchEvent(eventType, detail = null) {
            const event = new Event(eventType, { bubbles: true, cancelable: false });
            if (detail) {
                Object.assign(event, detail);
            }
            this.originalVideo.dispatchEvent(event);
        }

        /**
         * Clean up resources
         */
        destroy() {
            this.stopObserving();
            this.restoreTransparency();
            if (this._nativeCreated) {
                bridge.onVideoElementDestroyed(this.videoId);
                this._nativeCreated = false;
            }
        }
    }

    /**
     * Create property descriptors for video element proxy
     */
    function createPropertyDescriptors(state) {
        return {
            // Source property
            src: {
                get() { return state._src; },
                set(value) {
                    state._src = value;
                    if (value && bridge.shouldProxyUrl(value)) {
                        // Lazily create the native ExoPlayer only when actually needed
                        state.ensureNativePlayer();
                        state.isProxied = true;
                        bridge.setSource(state.videoId, value);
                        // Make the video element AND all ancestor containers transparent
                        // so the native TextureView below the WebView can show through.
                        // The OSD controls have their own styling, so they remain visible.
                        state.makeTransparent();
                        state.startObserving();
                    } else {
                        state.isProxied = false;
                        state.restoreTransparency();
                        state.stopObserving();
                    }
                }
            },

            // Current time
            currentTime: {
                get() { return state._currentTime; },
                set(value) {
                    state._currentTime = value;
                    if (state.isProxied) {
                        bridge.seek(state.videoId, Math.floor(value * 1000));
                    }
                }
            },

            // Duration (read-only from JS perspective)
            duration: {
                get() { return state._duration; },
                set(value) { state._duration = value; }
            },

            // Paused state
            paused: {
                get() { return state._paused; },
                set(value) { state._paused = value; }
            },

            // Ended state
            ended: {
                get() { return state._ended; },
                set(value) { state._ended = value; }
            },

            // Volume
            volume: {
                get() { return state._volume; },
                set(value) {
                    state._volume = Math.max(0, Math.min(1, value));
                    if (state.isProxied) {
                        bridge.setVolume(state.videoId, state._volume);
                    }
                }
            },

            // Muted
            muted: {
                get() { return state._muted; },
                set(value) {
                    state._muted = value;
                    if (state.isProxied) {
                        bridge.setVolume(state.videoId, value ? 0 : state._volume);
                    }
                }
            },

            // Playback rate
            playbackRate: {
                get() { return state._playbackRate; },
                set(value) {
                    state._playbackRate = value;
                    if (state.isProxied) {
                        bridge.setPlaybackRate(state.videoId, value);
                    }
                }
            },

            // Ready state
            readyState: {
                get() { return state._readyState; },
                set(value) { state._readyState = value; }
            },

            // Video dimensions
            videoWidth: {
                get() { return state._bounds.width; }
            },
            videoHeight: {
                get() { return state._bounds.height; }
            },

            // Buffered (stub)
            buffered: {
                get() {
                    return {
                        length: state._duration > 0 ? 1 : 0,
                        start: () => 0,
                        end: () => state._duration
                    };
                }
            },

            // Audio tracks - proxied from ExoPlayer
            audioTracks: {
                get() { return state._audioTrackList; }
            },

            // Text tracks - proxied from ExoPlayer
            textTracks: {
                get() { return state._textTrackList; }
            }
        };
    }

    /**
     * Wrap video element methods
     */
    function wrapVideoMethods(video, state) {
        const originalPlay = video.play.bind(video);
        const originalPause = video.pause.bind(video);
        const originalLoad = video.load.bind(video);

        video.play = function() {
            if (state.isProxied) {
                state._paused = false;
                state._ended = false;
                bridge.play(state.videoId);
                return Promise.resolve();
            }
            return originalPlay();
        };

        video.pause = function() {
            if (state.isProxied) {
                state._paused = true;
                bridge.pause(state.videoId);
                return;
            }
            return originalPause();
        };

        video.load = function() {
            if (state.isProxied) {
                // Reset state
                state._currentTime = 0;
                state._duration = 0;
                state._readyState = 0;
                state._ended = false;
                return;
            }
            return originalLoad();
        };

        // Request fullscreen handling
        const originalRequestFullscreen = video.requestFullscreen?.bind(video);
        if (originalRequestFullscreen) {
            video.requestFullscreen = function(options) {
                if (state.isProxied) {
                    state._fullscreen = true;
                    bridge.setFullscreen(state.videoId, true);
                    return Promise.resolve();
                }
                return originalRequestFullscreen(options);
            };
        }
    }

    /**
     * Proxy an existing video element
     */
    function proxyVideoElement(video) {
        // Skip if already proxied
        if (video._videoProxyState) {
            return video;
        }

        const videoId = generateVideoId();
        const state = new VideoProxyState(videoId, video);
        
        // Store state on video element
        video._videoProxyState = state;
        proxiedVideos.set(videoId, state);

        // Apply property descriptors
        const descriptors = createPropertyDescriptors(state);
        for (const [prop, descriptor] of Object.entries(descriptors)) {
            try {
                Object.defineProperty(video, prop, descriptor);
            } catch (e) {
                console.warn(`[VideoProxy] Failed to define property ${prop}:`, e);
            }
        }

        // Wrap methods
        wrapVideoMethods(video, state);

        // Handle removal from DOM
        const originalRemove = video.remove.bind(video);
        video.remove = function() {
            state.destroy();
            proxiedVideos.delete(videoId);
            return originalRemove();
        };

        console.log(`[VideoProxy] Proxied video element: ${videoId}`);
        return video;
    }

    /**
     * Intercept document.createElement to proxy video elements
     */
    const originalCreateElement = document.createElement.bind(document);
    document.createElement = function(tagName, options) {
        const element = originalCreateElement(tagName, options);
        if (tagName.toLowerCase() === 'video') {
            return proxyVideoElement(element);
        }
        return element;
    };

    /**
     * Proxy existing video elements on the page
     */
    function proxyExistingVideos() {
        const videos = document.querySelectorAll('video');
        videos.forEach(video => proxyVideoElement(video));
    }

    /**
     * Watch for dynamically added video elements
     */
    const domObserver = new MutationObserver((mutations) => {
        for (const mutation of mutations) {
            for (const node of mutation.addedNodes) {
                if (node.nodeType === Node.ELEMENT_NODE) {
                    if (node.tagName === 'VIDEO') {
                        proxyVideoElement(node);
                    }
                    // Check descendants
                    const videos = node.querySelectorAll?.('video');
                    if (videos) {
                        videos.forEach(video => proxyVideoElement(video));
                    }
                }
            }
            // Handle removed nodes
            for (const node of mutation.removedNodes) {
                if (node.nodeType === Node.ELEMENT_NODE) {
                    // Check the node itself
                    if (node.tagName === 'VIDEO' && node._videoProxyState) {
                        node._videoProxyState.destroy();
                        proxiedVideos.delete(node._videoProxyState.videoId);
                    }
                    // Also check descendant video elements inside removed containers
                    const descendantVideos = node.querySelectorAll?.('video');
                    if (descendantVideos) {
                        descendantVideos.forEach(video => {
                            if (video._videoProxyState) {
                                video._videoProxyState.destroy();
                                proxiedVideos.delete(video._videoProxyState.videoId);
                            }
                        });
                    }
                }
            }
        }
    });

    // Start observing DOM
    domObserver.observe(document.documentElement, {
        childList: true,
        subtree: true
    });

    /**
     * API for native code to call back into JavaScript
     */
    window.VideoProxyCallback = {
        /**
         * Called when native player state changes
         */
        onStateChange(videoId, state) {
            const proxyState = proxiedVideos.get(videoId);
            if (!proxyState) return;

            if (state.currentTime !== undefined) {
                proxyState._currentTime = state.currentTime / 1000;
                proxyState.dispatchEvent('timeupdate');
            }
            if (state.duration !== undefined) {
                proxyState._duration = state.duration / 1000;
                proxyState.dispatchEvent('durationchange');
            }
            if (state.paused !== undefined) {
                proxyState._paused = state.paused;
                proxyState.dispatchEvent(state.paused ? 'pause' : 'play');
            }
            if (state.ended !== undefined && state.ended) {
                proxyState._ended = true;
                proxyState._paused = true;
                proxyState.dispatchEvent('ended');
            }
            if (state.readyState !== undefined) {
                proxyState._readyState = state.readyState;
                if (state.readyState >= 1) {
                    proxyState.dispatchEvent('loadedmetadata');
                }
                if (state.readyState >= 3) {
                    proxyState.dispatchEvent('canplay');
                }
                if (state.readyState >= 4) {
                    proxyState.dispatchEvent('canplaythrough');
                }
            }
        },

        /**
         * Called when buffering state changes
         */
        onBuffering(videoId, isBuffering) {
            const proxyState = proxiedVideos.get(videoId);
            if (!proxyState) return;

            proxyState.dispatchEvent(isBuffering ? 'waiting' : 'playing');
        },

        /**
         * Called when an error occurs
         */
        onError(videoId, errorCode, errorMessage) {
            const proxyState = proxiedVideos.get(videoId);
            if (!proxyState) return;

            const error = new MediaError();
            error.code = errorCode;
            error.message = errorMessage;
            proxyState.originalVideo.error = error;
            proxyState.dispatchEvent('error');
        },

        /**
         * Called when fullscreen state changes from native
         */
        onFullscreenChange(videoId, isFullscreen) {
            const proxyState = proxiedVideos.get(videoId);
            if (!proxyState) return;

            proxyState._fullscreen = isFullscreen;
            document.dispatchEvent(new Event('fullscreenchange'));
        },

        /**
         * Called when ExoPlayer track information changes.
         * Updates the proxied audioTracks and textTracks lists.
         * @param {string} videoId - The video element ID
         * @param {string} tracksJson - JSON string with audioTracks and subtitleTracks arrays
         */
        onTracksChanged(videoId, tracksJson) {
            const proxyState = proxiedVideos.get(videoId);
            if (!proxyState) return;

            try {
                const tracks = typeof tracksJson === 'string' ? JSON.parse(tracksJson) : tracksJson;

                if (tracks.audioTracks && proxyState._audioTrackList) {
                    proxyState._audioTrackList._update(tracks.audioTracks);
                    console.log(`[VideoProxy] Updated ${tracks.audioTracks.length} audio tracks for ${videoId}`);
                }

                if (tracks.subtitleTracks && proxyState._textTrackList) {
                    proxyState._textTrackList._update(tracks.subtitleTracks);
                    console.log(`[VideoProxy] Updated ${tracks.subtitleTracks.length} subtitle tracks for ${videoId}`);
                }
            } catch (e) {
                console.error('[VideoProxy] Failed to parse tracks JSON:', e);
            }
        }
    };

    // Proxy existing videos when DOM is ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', proxyExistingVideos);
    } else {
        proxyExistingVideos();
    }

    // Handle fullscreen exit
    document.addEventListener('fullscreenchange', () => {
        if (!document.fullscreenElement) {
            proxiedVideos.forEach((state) => {
                if (state._fullscreen && state.isProxied) {
                    state._fullscreen = false;
                    bridge.setFullscreen(state.videoId, false);
                }
            });
        }
    });

    /**
     * Inject "ExoPlayer Debug" toggle into Jellyfin's OSD settings menu.
     *
     * Strategy:
     * 1. Find the OSD settings button (gear icon / "..." button) and intercept its click.
     * 2. When a settings dialog or actionSheet appears, inject our menu item.
     * 3. Use MutationObserver as a fallback to catch dynamically created menus.
     */
    (function injectDebugMenuItem() {
        let debugActive = false;

        /**
         * Create a menu item by cloning an existing item's structure,
         * so it inherits the same classes, icons layout and CSS.
         * Falls back to a plain button if no template item is available.
         * @param {Element} container - The menu container to find a template item from
         * @returns {Element} The menu item button
         */
        function createMenuItem(container) {
            const template = container.querySelector('button');
            let btn;

            if (template) {
                // Clone the existing item to get the exact same structure and classes
                btn = template.cloneNode(true);
                btn.className = template.className;
                // Add our marker class
                btn.classList.add('btnVideoProxyDebug');
                // Remove any id to avoid duplicates
                btn.removeAttribute('id');
                btn.removeAttribute('data-id');
                // Replace all text content while preserving icon structure
                const textEl = btn.querySelector('.actionSheetItemText') ||
                    btn.querySelector('[class*="ItemText"]') ||
                    btn.lastElementChild ||
                    btn;
                textEl.textContent = debugActive ? 'ExoPlayer Debug ✓' : 'ExoPlayer Debug';
                // Update icon if present
                const iconEl = btn.querySelector('.material-icons, [class*="Icon"]');
                if (iconEl && iconEl !== textEl) {
                    iconEl.textContent = 'bug_report';
                }
                // Clear any checked/selected state
                btn.classList.remove('selected', 'active', 'checked');
                btn.removeAttribute('aria-checked');
                btn.removeAttribute('data-action');
            } else {
                // Fallback: create a plain button
                btn = document.createElement('button');
                btn.className = 'btnVideoProxyDebug';
                btn.type = 'button';
                btn.textContent = debugActive ? 'ExoPlayer Debug ✓' : 'ExoPlayer Debug';
            }

            // Replace all event listeners by cloning without listeners
            const clean = btn.cloneNode(true);
            clean.addEventListener('click', function(e) {
                e.preventDefault();
                e.stopPropagation();
                debugActive = !debugActive;
                try {
                    bridge.toggleDebugInfo();
                } catch (err) {
                    console.error('[VideoProxy] toggleDebugInfo failed:', err);
                }
                closeCurrentMenu();
            });

            return clean;
        }

        /**
         * Close the currently open actionSheet / dialog.
         * Jellyfin web uses history-based dialog management: opening a dialog
         * pushes a history state, so history.back() reliably closes it.
         */
        function closeCurrentMenu() {
            try {
                history.back();
            } catch (e) {
                // Fallback: dispatch Escape key
                document.dispatchEvent(new KeyboardEvent('keydown', {
                    key: 'Escape',
                    bubbles: true,
                    cancelable: true,
                }));
            }
        }

        /**
         * Try to inject our menu item into an actionSheet or dialog.
         * @param {Element} container - The menu/dialog element
         */
        function tryInjectIntoMenu(container) {
            // Skip if already injected
            if (container.querySelector('.btnVideoProxyDebug')) return;
            // Only inject when video is active
            if (!proxiedVideos.size && !document.querySelector('video')) return;

            // Look for the items container within the actionSheet or dialog
            const itemsContainer = container.querySelector('.actionSheetScroller') ||
                container.querySelector('.dialogContent') ||
                container.querySelector('.formDialogContent') ||
                container;

            if (!itemsContainer) return;

            // Heuristic: check if this looks like a video player settings menu
            // by looking for playback-related keywords in button text
            const buttons = itemsContainer.querySelectorAll('button, .listItem, [data-action]');
            if (buttons.length === 0) return;

            const menuText = itemsContainer.textContent.toLowerCase();
            const isPlayerMenu = menuText.includes('quality') ||
                menuText.includes('speed') ||
                menuText.includes('audio') ||
                menuText.includes('subtitle') ||
                menuText.includes('playback') ||
                menuText.includes('stats') ||
                menuText.includes('stream') ||
                // Also check if we're in video OSD context
                !!document.querySelector('.videoOsdBottom, [class*="videoOsd"]');

            if (!isPlayerMenu) return;

            // Add debug menu item at the end of the menu
            const menuItem = createMenuItem(itemsContainer);
            itemsContainer.appendChild(menuItem);

            // Expand the scroller's max-height to accommodate the injected item
            // and shift the entire menu up if it overflows the viewport bottom.
            requestAnimationFrame(() => {
                const itemHeight = menuItem.offsetHeight;
                if (!itemHeight) return;

                // 1. Expand scroller so the new item is scrollable / visible
                const scroller = container.querySelector('.actionSheetScroller') || itemsContainer;
                const scrollerStyle = window.getComputedStyle(scroller);
                const currentMax = parseInt(scrollerStyle.maxHeight, 10);
                if (currentMax && !isNaN(currentMax)) {
                    scroller.style.maxHeight = (currentMax + itemHeight) + 'px';
                }

                // 2. If the menu now overflows below the viewport, shift it up
                const sheet = container.closest('.actionSheet') || container;
                const rect = sheet.getBoundingClientRect();
                const bottomOverflow = rect.bottom - window.innerHeight;
                if (bottomOverflow > 0) {
                    const padding = 8; // keep some breathing room from screen edge
                    const sheetStyle = window.getComputedStyle(sheet);
                    // Prefer adjusting margin-top (works for both fixed and absolute positioning)
                    const currentMargin = parseInt(sheetStyle.marginTop, 10) || 0;
                    sheet.style.marginTop = (currentMargin - bottomOverflow - padding - 20) + 'px';
                }
            });

            console.log('[VideoProxy] Debug menu item injected into settings menu');
        }

        // Observe DOM for new actionSheets / dialogs appearing
        const menuObserver = new MutationObserver((mutations) => {
            for (const mutation of mutations) {
                for (const node of mutation.addedNodes) {
                    if (node.nodeType !== Node.ELEMENT_NODE) continue;

                    // Direct match: the added node is an actionSheet or dialog
                    if (node.classList) {
                        if (node.classList.contains('actionSheet') ||
                            node.classList.contains('dialog') ||
                            node.classList.contains('dialogContainer') ||
                            node.classList.contains('actionsheet-scroller')) {
                            // Small delay to let the menu populate its items
                            setTimeout(() => tryInjectIntoMenu(node), 100);
                            continue;
                        }
                    }

                    // Indirect match: the added node contains an actionSheet or dialog
                    const menus = node.querySelectorAll?.('.actionSheet, .dialog, .dialogContainer');
                    if (menus && menus.length > 0) {
                        menus.forEach(menu => {
                            setTimeout(() => tryInjectIntoMenu(menu), 100);
                        });
                    }
                }
            }
        });

        menuObserver.observe(document.documentElement, {
            childList: true,
            subtree: true
        });

        console.log('[VideoProxy] Debug menu injection initialized');
    })();

    console.log('[VideoProxy] Video element proxy initialized');
})();

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

    // AVC support alone does not imply High 10 support. Keep server-transcoded
    // playback in the browser when Android cannot decode the original stream.
    const codecCapabilities = JSON.parse(window.NativeInterface.getCodecCapabilities());

    function isHigh10Stream(stream) {
        return stream?.Codec?.toLowerCase() === 'h264' &&
            (stream.Profile?.toLowerCase() === 'high 10' || stream.BitDepth === 10);
    }

    // NijiLucid imports decoded HTML video frames into WebGPU. SurfaceView
    // playback cannot supply those frames, so negotiate a browser stream while
    // enhancement is enabled, including when the preference was saved earlier.
    const enhancementSettingsKey = 'jellyfin.nijilucid.v1';
    let playbackItem = null;

    function enhancementEnabled() {
        const seriesId = playbackItem?.SeriesId || (playbackItem?.Type === 'Series' ? playbackItem.Id : null);
        const serverId = playbackItem?.ServerId;
        // Match the web client's per-series settings. A series without a saved
        // profile starts with enhancement off, even if the global setting is on.
        const key = seriesId && serverId
            ? `${enhancementSettingsKey}.series.${encodeURIComponent(serverId)}.${encodeURIComponent(seriesId)}`
            : enhancementSettingsKey;
        try {
            const settings = JSON.parse(localStorage.getItem(key) || 'null');
            return settings && !Array.isArray(settings) && settings.enabled === true;
        } catch (_) {
            return false;
        }
    }

    let webPlaybackRequired = !!enhancementEnabled();
    let reloadPlayback = null;
    let modeChanges = Promise.resolve();
    window.VideoProxy = Object.freeze({
        preparePlayback(item) {
            // Called before the browser builds its codec profile or requests a
            // stream, so both negotiation and source assignment use one policy.
            playbackItem = item;
            webPlaybackRequired = !!enhancementEnabled();
        },
        setPlaybackReloader(reload) {
            reloadPlayback = reload;
        }
    });

    function updatePlaybackMode() {
        // Read the latest preference when each queued change starts. Changing the
        // policy during an in-flight PlaybackInfo request would mix the browser
        // profile with the native source handler (or the reverse).
        modeChanges = modeChanges.then(async () => {
            const required = !!enhancementEnabled();
            if (required === webPlaybackRequired) return;
            const previous = webPlaybackRequired;
            webPlaybackRequired = required;
            try {
                await reloadPlayback?.();
            } catch (_) {
                webPlaybackRequired = previous;
                console.warn('[VideoProxy] Could not switch video decoding mode');
            }
        });
    }
    window.addEventListener('nijilucid-settings', updatePlaybackMode);
    window.addEventListener('storage', event => {
        if (event.key === enhancementSettingsKey || event.key?.startsWith(enhancementSettingsKey + '.series.') || event.key === null) updatePlaybackMode();
    });

    // ─── Override canPlayType to report ExoPlayer audio capabilities ───
    //
    // The Jellyfin web client's HtmlVideoPlayer.getSupportedAudioStreams()
    // filters audio streams by the browser's DeviceProfile (built from
    // canPlayType results). Chrome/Android doesn't natively support codecs
    // like AC3, DTS, TrueHD, etc., so those audio tracks get filtered out.
    // When only ≤1 audio stream passes the filter,
    // setAudioStreamIndex() returns early without switching.
    //
    // By overriding canPlayType to report support for all audio codecs
    // that ExoPlayer can decode, we ensure the DeviceProfile includes them
    // and getSupportedAudioStreams() returns ALL audio tracks.
    const originalCanPlayType = HTMLMediaElement.prototype.canPlayType;
    HTMLMediaElement.prototype.canPlayType = function(type) {
        const original = originalCanPlayType.call(this, type);
        if (original || webPlaybackRequired) return original;

        // ExoPlayer-supported audio codecs that Chrome typically rejects
        const lowerType = type.toLowerCase();
        const exoPlayerAudioCodecs = [
            'ac-3', 'ec-3', 'ac3', 'eac3',       // Dolby Digital / E-AC3
            'dtse', 'dtsc', 'dtsh', 'dts',        // DTS variants
            'flac',                                 // FLAC
            'truehd', 'mlp',                        // Dolby TrueHD
            'alac',                                 // Apple Lossless
            'mp2', 'mp1',                           // MPEG audio layers
            'pcm', 'lpcm',                          // PCM variants
            'wma',                                  // Windows Media Audio
        ];

        for (const codec of exoPlayerAudioCodecs) {
            if (lowerType.includes(codec)) {
                return 'probably';
            }
        }

        return original;
    };
    console.log('[VideoProxy] canPlayType overridden for ExoPlayer audio codecs');

    // Store for proxied video elements
    const proxiedVideos = new Map();
    let videoIdCounter = 0;
    const pageId = Date.now().toString(36) + Math.random().toString(36).slice(2);
    const playbackContexts = new Map();
    const transparentAncestors = new WeakMap();

    // --- Playback context capture via fetch interception ---
    // When the Jellyfin Web client calls the PlaybackInfo API before starting
    // playback, we capture the item ID, media source ID, and MediaStreams
    // so that when a blob: URL is set (from hls.js/MSE transcoding), we can
    // resolve the real direct-play URL on the native side.
    // We also capture audio stream indices for proper track mapping.
    let currentPlaybackContext = null;

    /**
     * Extract playback context from a PlaybackInfo API response.
     * Captures item ID, media source info, and audio/subtitle stream
     * indices for mapping between Jellyfin server indices and ExoPlayer
     * track group indices.
     */
    function extractPlaybackContext(itemId, data, mediaSource) {

        // Collect audio stream indices (server MediaStream.Index values)
        // in the order they appear. This establishes the mapping:
        //   audioStreamIndices[0] → ExoPlayer audio group 0
        //   audioStreamIndices[1] → ExoPlayer audio group 1
        //   etc.
        const audioStreamIndices = [];
        const subtitleStreamIndices = [];
        // Subtitle stream details for codec/delivery matching
        const subtitleStreams = [];
        if (mediaSource && mediaSource.MediaStreams) {
            for (const stream of mediaSource.MediaStreams) {
                if (stream.Type === 'Audio') {
                    audioStreamIndices.push(stream.Index);
                } else if (stream.Type === 'Subtitle') {
                    subtitleStreamIndices.push(stream.Index);
                    subtitleStreams.push({
                        index: stream.Index,
                        codec: (stream.Codec || stream.codec || '').toLowerCase(),
                        deliveryMethod: stream.DeliveryMethod || stream.deliveryMethod || '',
                        isExternal: !!(stream.IsExternal ?? stream.isExternal),
                        language: stream.Language || stream.language || '',
                        title: stream.DisplayTitle || stream.displayTitle || stream.Title || stream.title || '',
                    });
                }
            }
        }

        const videoStream = mediaSource?.MediaStreams?.find(stream => stream.Type === 'Video');
        const isHigh10 = isHigh10Stream(videoStream);
        const nativeVideoSupported = !isHigh10 || codecCapabilities.h264High10Supported === true;

        const defaultSubtitleStreamIndex = mediaSource
            ? (mediaSource.DefaultSubtitleStreamIndex ?? -1) : -1;

        return {
            itemId: itemId,
            mediaSourceId: mediaSource ? mediaSource.Id : '',
            playSessionId: data.PlaySessionId || '',
            nativeVideoSupported: nativeVideoSupported,
            audioStreamIndices: audioStreamIndices,
            subtitleStreamIndices: subtitleStreamIndices,
            subtitleStreams: subtitleStreams,
            defaultSubtitleStreamIndex: defaultSubtitleStreamIndex,
        };
    }

    const normalizeId = value => String(value || '').replace(/-/g, '').toLowerCase();

    function capturePlaybackContext(itemId, data, url, body) {
        let requestedSource = new URL(url, document.baseURI).searchParams.get('MediaSourceId');
        try {
            const request = typeof body === 'string' ? JSON.parse(body) : body;
            requestedSource = request?.MediaSourceId || request?.mediaSourceId || requestedSource;
        } catch (_) { /* The request body may not be JSON. */ }
        const contexts = (data.MediaSources || []).map(source => extractPlaybackContext(itemId, data, source));
        for (const context of contexts) {
            playbackContexts.set(normalizeId(itemId) + ':' + normalizeId(context.mediaSourceId), context);
        }
        while (playbackContexts.size > 32) playbackContexts.delete(playbackContexts.keys().next().value);
        currentPlaybackContext = contexts.find(context => normalizeId(context.mediaSourceId) === normalizeId(requestedSource))
            || contexts[0] || null;
    }

    function contextForSource(src) {
        if (src.startsWith('blob:')) return currentPlaybackContext;
        try {
            const url = new URL(src, document.baseURI);
            const itemId = url.pathname.match(/\/videos\/([^/]+)/i)?.[1];
            if (!itemId) return null;
            const sourceId = url.searchParams.get('MediaSourceId') || url.searchParams.get('mediaSourceId');
            if (sourceId) return playbackContexts.get(normalizeId(itemId) + ':' + normalizeId(sourceId)) || null;
            return normalizeId(currentPlaybackContext?.itemId) === normalizeId(itemId) ? currentPlaybackContext : null;
        } catch (_) { return null; }
    }

    /**
     * Detect when the web client fetches a subtitle for an embedded SRT track.
     * Sets _activeNativeSubtitleIndex on the proxy state so the mode setter
     * can redirect to ExoPlayer native rendering instead of web DOM rendering.
     *
     * This handles the "External" delivery case: the server extracts embedded
     * SRT from the container and serves it via API, but ExoPlayer already has
     * the track embedded — it's more reliable to let ExoPlayer render it
     * natively via SubtitleView.
     */
    function handleSubtitleFetched(url) {
        const match = url.match(/\/Videos\/([^/]+)\/([^/]+)\/Subtitles\/(\d+)\//i);
        if (!match) return;
        for (const proxyState of proxiedVideos.values()) {
            const ctx = proxyState._playbackContext;
            if (!proxyState.isProxied || !ctx || normalizeId(ctx.itemId) !== normalizeId(match[1]) ||
                normalizeId(ctx.mediaSourceId) !== normalizeId(match[2])) continue;
            const stream = ctx.subtitleStreams.find(s => s.index === Number(match[3]));
            proxyState._activeNativeSubtitleIndex = -1;
            if (stream && !stream.isExternal && NATIVE_SUBTITLE_SERVER_CODECS.includes(stream.codec)) {
                const track = proxyState._textTrackList._tracks.find(t => t._source !== 'web' && t.id === String(stream.index));
                if (track) proxyState._activeNativeSubtitleIndex = track._index;
            }
        }
    }

    function preparePlaybackRequest(url, body) {
        if (webPlaybackRequired || !bridge.isEnabled() || !codecCapabilities.h264High10Supported ||
            typeof body !== 'string' || !/\/Items\/[^/]+\/PlaybackInfo/i.test(url)) return body;
        // The browser profile rejects High 10 and MKV. Advertise the native
        // decoder before negotiation so the server returns the original file.
        const itemId = String(url).match(/\/Items\/([^/]+)\/PlaybackInfo/i)?.[1];
        if (normalizeId(itemId) !== normalizeId(playbackItem?.Id)) return body;
        const streams = playbackItem?.MediaStreams || playbackItem?.MediaSources?.flatMap(source => source.MediaStreams || []) || [];
        if (!streams.some(stream => stream.Type === 'Video' && isHigh10Stream(stream))) return body;
        try {
            const request = JSON.parse(body);
            const profile = request.DeviceProfile;
            if (!profile) return body;
            profile.DirectPlayProfiles = [...(profile.DirectPlayProfiles || []), ...(codecCapabilities.h264DirectPlayProfiles || [])];
            for (const codecProfile of profile.CodecProfiles || []) {
                if (codecProfile.Type !== 'Video' || !codecProfile.Codec?.split(',').includes('h264')) continue;
                for (const condition of codecProfile.Conditions || []) {
                    if (condition.Property === 'VideoProfile' && condition.Condition === 'EqualsAny' &&
                        !condition.Value.toLowerCase().split('|').includes('high 10')) condition.Value += '|high 10';
                }
            }
            return JSON.stringify(request);
        } catch (_) {
            return body;
        }
    }

    const originalFetch = window.fetch;
    window.fetch = async function(...args) {
        // Detect subtitle API fetches BEFORE awaiting response, so
        // _activeNativeSubtitleIndex is set before the web client processes
        // the response. This is critical for the mode setter to redirect
        // to ExoPlayer native rendering.
        try {
            const preUrl = typeof args[0] === 'string' ? args[0] :
                            (args[0] instanceof Request ? args[0].url : null);
            if (preUrl) {
                const subtitleMatch = preUrl.match(/\/Subtitles\/(\d+)\//);
                if (subtitleMatch) {
                    handleSubtitleFetched(preUrl);
                }
            }
        } catch (e) {
            console.warn('[VideoProxy] Error in pre-fetch subtitle detection:', e);
        }

        const requestUrl = typeof args[0] === 'string' ? args[0] : args[0]?.url;
        if (args[0] instanceof Request && !args[1]?.body && /\/PlaybackInfo/i.test(requestUrl)) {
            const body = await args[0].clone().text();
            const prepared = preparePlaybackRequest(requestUrl, body);
            if (prepared !== body) args[0] = new Request(args[0], { body: prepared });
        } else if (args[1]?.body) {
            args[1] = { ...args[1], body: preparePlaybackRequest(requestUrl, args[1].body) };
        }
        const response = await originalFetch.apply(this, args);

        try {
            const url = typeof args[0] === 'string' ? args[0] :
                         (args[0] instanceof Request ? args[0].url : null);

            if (url && url.includes('/PlaybackInfo')) {
                // Extract item ID from URL: /Items/{itemId}/PlaybackInfo
                const match = url.match(/\/Items\/([a-f0-9-]+)\/PlaybackInfo/i);
                if (match) {
                    const clonedResponse = response.clone();
                    const data = await clonedResponse.json();
                    capturePlaybackContext(match[1], data, url, args[1]?.body);
                    console.log('[VideoProxy] Captured playback context:',
                        JSON.stringify(currentPlaybackContext));
                }
            }
        } catch (e) {
            // Don't let our interception break normal fetch behavior
            console.warn('[VideoProxy] Error in fetch interception:', e);
        }

        return response;
    };

    // Also intercept XMLHttpRequest for compatibility with older web client versions
    const originalXHROpen = XMLHttpRequest.prototype.open;
    const originalXHRSend = XMLHttpRequest.prototype.send;

    XMLHttpRequest.prototype.open = function(method, url, ...rest) {
        this._videoProxyUrl = url;
        return originalXHROpen.call(this, method, url, ...rest);
    };

    XMLHttpRequest.prototype.send = function(body) {
        const xhrUrl = this._videoProxyUrl;
        body = preparePlaybackRequest(xhrUrl, body);
        if (xhrUrl && typeof xhrUrl === 'string') {
            if (xhrUrl.includes('/PlaybackInfo')) {
                this.addEventListener('load', function() {
                    try {
                        const match = xhrUrl.match(/\/Items\/([a-f0-9-]+)\/PlaybackInfo/i);
                        if (match && this.responseText) {
                            const data = JSON.parse(this.responseText);
                            capturePlaybackContext(match[1], data, xhrUrl, body);
                            console.log('[VideoProxy] Captured playback context (XHR):',
                                JSON.stringify(currentPlaybackContext));
                        }
                    } catch (e) {
                        console.warn('[VideoProxy] Error in XHR interception:', e);
                    }
                });
            }

            // Detect subtitle API fetches via XHR
            const subtitleMatch = xhrUrl.match(/\/Subtitles\/(\d+)\//);
            if (subtitleMatch) {
                handleSubtitleFetched(xhrUrl);
            }
        }
        return originalXHRSend.call(this, body);
    };

    // Generate unique video ID
    function generateVideoId() {
        return 'video_proxy_' + pageId + '_' + (++videoIdCounter);
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
            // Use the server's MediaStream.Index as the track id when available.
            // This aligns with how Safari exposes native audioTrack.id, and
            // ensures getTrackById(serverIndex) works if any code uses it.
            this.id = String(info.serverStreamIndex ?? info.index);
            this.kind = info.isDefault ? 'main' : 'alternative';
            this.label = info.label || '';
            this.language = info.language || '';
            this._enabled = info.isSelected || false;
            // _index is the ExoPlayer track group index (0-based among audio groups)
            this._index = info.index;
            this.channelCount = info.channelCount || 0;
            this.codec = info.codec || '';
        }

        get enabled() { return this._enabled; }
        set enabled(value) {
            const boolVal = !!value;
            if (boolVal === this._enabled) return;
            if (boolVal) {
                // Disable all other tracks in the list
                this._trackList._tracks.forEach(t => { t._enabled = false; });
                this._enabled = true;
                console.log(`[VideoProxy] Audio track ${this._index} (id=${this.id}) enabled for ${this._trackList._videoId}`);
                bridge.setAudioTrack(this._trackList._videoId, this._index);
                this._trackList._dispatchChange();
            } else {
                // Audio must have at least one enabled track; ignore disable
            }
        }
    }

    /**
     * Proxy AudioTrackList - mimics the HTML5 AudioTrackList interface.
     * Supports indexed access, length, getTrackById, iteration, and change events.
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
                    // Support for...of iteration (Array.from also uses this)
                    if (prop === Symbol.iterator) {
                        return function() { return target._tracks[Symbol.iterator](); };
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

        /**
         * Update the track list from ExoPlayer track info.
         * @param {Array} trackInfos - Track info objects from ExoPlayer
         * @param {Array} [serverStreamIndices] - Server MediaStream.Index values
         *   in the same order as the audio streams in the container. Maps
         *   ExoPlayer track group indices → server stream indices.
         */
        _update(trackInfos, serverStreamIndices) {
            this._tracks = trackInfos.map((info, i) => {
                // Enrich with server stream index for proper id/mapping
                const enriched = Object.assign({}, info, {
                    serverStreamIndex: (serverStreamIndices && serverStreamIndices[i] !== undefined)
                        ? serverStreamIndices[i]
                        : undefined,
                });
                return new ProxyAudioTrack(this, enriched);
            });
            this._dispatchChange();
        }

        _dispatchChange() {
            const event = new Event('change');
            this._listeners.change.forEach(l => l(event));
            if (typeof this.onchange === 'function') this.onchange(event);
        }
    }

    /**
     * Create a TextTrackCueList-like wrapper around an array of cues.
     * Supports indexed access, length, iteration, and getCueById.
     */
    function createCueList(cues) {
        const list = Array.isArray(cues) ? [...cues] : [];
        list.getCueById = function(id) {
            return list.find(c => c.id === id) || null;
        };
        return list;
    }

    /**
     * Subtitle codecs that ExoPlayer renders natively via SubtitleView.
     * For these codecs, ExoPlayer decodes the embedded subtitle track and
     * renders it directly — the web client's addCue is a no-op to prevent
     * dual subtitle display.
     *
     * All other codecs (ASS/SSA, VTT, PGS, etc.) are rendered by the
     * Jellyfin web client's DOM-based renderer: ExoPlayer's text tracks
     * remain disabled and the web client downloads + renders via addCue.
     */
    const NATIVE_SUBTITLE_CODECS = [
        'application/x-subrip',   // SubRip (.srt)
    ];

    /**
     * Jellyfin server-side codec names that correspond to NATIVE_SUBTITLE_CODECS.
     * Used to match PlaybackInfo stream data with ExoPlayer MIME types.
     */
    const NATIVE_SUBTITLE_SERVER_CODECS = ['srt', 'subrip', 'mov_text', 'tx3g', 'text'];

    /**
     * Auto-enable ExoPlayer's subtitle track if the user's selected subtitle
     * is SRT/SubRip and embedded in the container.
     *
     * For embedded SRT, the web client relies on browser-native text track
     * rendering, which doesn't work with the ExoPlayer proxy (the browser
     * never decodes the container). ExoPlayer must render SRT natively via
     * SubtitleView instead.
     *
     * For external SRT files, the web client downloads them from the server
     * API and renders via its own DOM overlay — no ExoPlayer intervention.
     *
     * For non-SRT codecs, the web client downloads and renders via DOM.
     */
    function autoEnableSubtitleTrack(proxyState) {
        const ctx = proxyState._playbackContext;
        if (proxyState._subtitleSelectionInitialized || !ctx || !proxyState._textTrackList.length) return;
        proxyState._subtitleSelectionInitialized = true;
        if (ctx.defaultSubtitleStreamIndex < 0) return;

        // Find the selected subtitle stream in the server's metadata
        const selectedStream = ctx.subtitleStreams.find(
            s => s.index === ctx.defaultSubtitleStreamIndex
        );
        if (!selectedStream) return;

        // Only auto-enable for SRT/SubRip (ExoPlayer renders natively)
        if (!NATIVE_SUBTITLE_SERVER_CODECS.includes(selectedStream.codec)) {
            console.log(`[VideoProxy] Selected subtitle is ${selectedStream.codec} → web rendering`);
            return;
        }

        // External subtitle files are downloaded and rendered by the web
        // client. Only auto-enable ExoPlayer for embedded (non-external) SRT.
        if (selectedStream.isExternal) {
            console.log('[VideoProxy] Default subtitle is external SRT file → web rendering');
            return;
        }

        // Find the ProxyTextTrack by server stream index (ID-based lookup).
        // This is more reliable than positional mapping because ProxyTextTrack.id
        // is set to the server stream index during _update().
        const tracks = proxyState._textTrackList._tracks;
        const targetTrack = tracks.find(
            t => t._source !== 'web' && t.id === String(ctx.defaultSubtitleStreamIndex)
        );
        if (!targetTrack) {
            console.log('[VideoProxy] No ExoPlayer text track for server index ' +
                ctx.defaultSubtitleStreamIndex);
            return;
        }

        console.log(`[VideoProxy] Auto-enabling embedded SRT subtitle: ` +
            `server index=${ctx.defaultSubtitleStreamIndex}, ` +
            `exo group=${targetTrack._index}, codec=${targetTrack.codec}`);

        targetTrack.mode = 'showing';
    }

    /**
     * Proxy TextTrack - mimics the HTML5 TextTrack interface.
     *
     * Subtitle rendering is routed based on codec:
     * - SubRip (application/x-subrip): ExoPlayer renders natively via
     *   SubtitleView. addCue() is a no-op. The bridge is called to enable
     *   the text track in ExoPlayer.
     * - All other codecs: The web client downloads subtitle data and renders
     *   via DOM. addCue() stores cues, activeCues/cuechange drive rendering.
     *   ExoPlayer's text tracks remain disabled.
     */
    class ProxyTextTrack {
        constructor(trackList, info) {
            this._trackList = trackList;
            this.id = String(info.serverStreamIndex ?? info.index);
            this.kind = 'subtitles';
            this.label = info.label || '';
            this.language = info.language || '';
            this._mode = info.isSelected ? 'showing' : 'disabled';
            // _index is the ExoPlayer track group index (0-based among subtitle groups)
            this._index = info.index;
            // ExoPlayer MIME type (may be internal like 'application/x-media3-cues')
            this.codec = info.codec || '';
            // Server-side codec from Jellyfin API (e.g. 'srt', 'ass', 'subrip')
            this._serverCodec = info._serverCodec || '';
            // Jellyfin delivery method ('Embed', 'External', 'Encode')
            this._deliveryMethod = info._deliveryMethod || '';
            this._cues = [];
            this._lastActiveCueKey = '';
            this._nativeRendering = false;
            // 'exoplayer' for tracks reported by ExoPlayer, 'web' for addTextTrack()
            this._source = info._source || 'exoplayer';
            this._listeners = { cuechange: [] };
            this.oncuechange = null;
        }

        /**
         * Whether this track's codec is handled by ExoPlayer's native
         * subtitle renderer (SubtitleView) rather than the web client.
         *
         * Uses the server-side codec (from Jellyfin API) as the primary check
         * because Media3 reports all parsed subtitles as 'application/x-media3-cues'
         * regardless of the original format.
         */
        _isNativeRenderable() {
            if (this._serverCodec) {
                return NATIVE_SUBTITLE_SERVER_CODECS.includes(this._serverCodec);
            }
            return NATIVE_SUBTITLE_CODECS.includes(this.codec);
        }

        /** Return all cues as a TextTrackCueList-like object. */
        get cues() {
            return createCueList(this._cues);
        }

        /** Return cues active at the current playback time. */
        get activeCues() {
            // When ExoPlayer renders this track natively, cues are empty
            if (this._mode === 'disabled' || this._nativeRendering) return createCueList([]);
            const currentTime = this._trackList._getCurrentTime();
            const active = this._cues.filter(cue =>
                currentTime >= cue.startTime && currentTime < cue.endTime
            );
            return createCueList(active);
        }

        get mode() { return this._mode; }
        set mode(value) {
            const state = proxiedVideos.get(this._trackList._videoId);
            if (state) state._subtitleSelectionInitialized = true;
            if (value === this._mode) return;
            this._mode = value;

            if (value === 'showing' || value === 'hidden') {
                // Disable all other tracks
                this._trackList._tracks.forEach(t => {
                    if (t !== this) {
                        t._mode = 'disabled';
                        t._nativeRendering = false;
                    }
                });

                if (this._isNativeRenderable()) {
                    // ExoPlayer track with native-renderable codec (e.g. SRT)
                    this._nativeRendering = true;
                    bridge.setSubtitleTrack(this._trackList._videoId, this._index);
                    console.log(`[VideoProxy] Subtitle track ${this._index} (server=${this._serverCodec}) → ExoPlayer native`);
                } else {
                    // Check for native subtitle override: if the web client is
                    // loading a subtitle that maps to an embedded SRT track in
                    // ExoPlayer, redirect to native rendering via SubtitleView.
                    const proxyState = proxiedVideos.get(this._trackList._videoId);
                    let nativeIdx = proxyState?._activeNativeSubtitleIndex ?? -1;

                    if (nativeIdx >= 0) {
                        this._nativeRendering = true;
                        bridge.setSubtitleTrack(this._trackList._videoId, nativeIdx);
                        console.log(`[VideoProxy] Web track redirected → ExoPlayer group ${nativeIdx}`);
                    } else {
                        this._nativeRendering = false;
                        bridge.disableSubtitleTrack(this._trackList._videoId);
                        console.log(`[VideoProxy] Subtitle track ${this._index} → WebView DOM`);
                    }
                }
            } else if (value === 'disabled') {
                this._nativeRendering = false;
                // If no tracks are showing, disable ExoPlayer text tracks
                const anyShowing = this._trackList._tracks.some(t =>
                    t._mode === 'showing' || t._mode === 'hidden'
                );
                if (!anyShowing) {
                    bridge.disableSubtitleTrack(this._trackList._videoId);
                    // Clear native override so next selection starts fresh
                    const ps = proxiedVideos.get(this._trackList._videoId);
                    if (ps) ps._activeNativeSubtitleIndex = -1;
                }
            }

            this._trackList._dispatchChange();
        }

        /**
         * Add a cue to this text track.
         * No-op when ExoPlayer renders this track natively (prevents dual
         * subtitle display). For non-native tracks, cues are stored and
         * exposed via activeCues so the web client can render them.
         */
        addCue(cue) {
            // Jellyfin reuses existing tracks for downloaded cues, but only
            // disables tracks marked manualTrack when subtitles are turned off.
            // Mark an adopted embedded track too, including native-rendered cues.
            if (this._source === 'exoplayer' && !this.label.includes('manualTrack')) {
                this.label = this.label ? `${this.label} manualTrack` : 'manualTrack';
            }
            if (this._nativeRendering) return;
            this._cues.push(cue);
        }

        /**
         * Remove a cue from this text track.
         */
        removeCue(cue) {
            const idx = this._cues.indexOf(cue);
            if (idx >= 0) this._cues.splice(idx, 1);
        }

        /**
         * Check if the set of active cues has changed and fire cuechange
         * if so. Called periodically when currentTime updates.
         */
        _checkCueChange() {
            // Skip for native-rendered tracks (ExoPlayer handles them)
            if (this._mode === 'disabled' || this._nativeRendering || this._cues.length === 0) return;
            const currentTime = this._trackList._getCurrentTime();
            const active = this._cues.filter(cue =>
                currentTime >= cue.startTime && currentTime < cue.endTime
            );
            // Build a compact key from the active cue set for dirty-checking
            const key = active.map(c => `${c.startTime}:${c.endTime}`).join('|');
            if (key !== this._lastActiveCueKey) {
                this._lastActiveCueKey = key;
                const event = new Event('cuechange');
                this._listeners.cuechange.forEach(l => l(event));
                if (typeof this.oncuechange === 'function') this.oncuechange(event);
            }
        }

        addEventListener(type, listener) {
            if (this._listeners[type]) this._listeners[type].push(listener);
        }

        removeEventListener(type, listener) {
            if (this._listeners[type]) {
                this._listeners[type] = this._listeners[type].filter(l => l !== listener);
            }
        }
    }

    /**
     * Proxy TextTrackList - mimics the HTML5 TextTrackList interface.
     * Supports indexed access, length, getTrackById, and change events.
     */
    class ProxyTextTrackList {
        constructor(videoId) {
            this._videoId = videoId;
            this._tracks = [];
            this._cueOverlay = null;
            this._renderedCues = [];
            this._listeners = { change: [], addtrack: [], removetrack: [] };
            this.onchange = null;
            this.onaddtrack = null;
            this.onremovetrack = null;

            return new Proxy(this, {
                get(target, prop, receiver) {
                    if (typeof prop === 'string' && /^\d+$/.test(prop)) {
                        return target._tracks[parseInt(prop, 10)];
                    }
                    if (prop === Symbol.iterator) {
                        return function() { return target._tracks[Symbol.iterator](); };
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

        /** Get the current playback time (seconds) from the proxied video. */
        _getCurrentTime() {
            const state = proxiedVideos.get(this._videoId);
            return state ? state._currentTime : 0;
        }

        /**
         * Check all tracks for cue changes.
         * Called after currentTime updates to fire cuechange events.
         */
        _checkCueChanges() {
            this._tracks.forEach(track => track._checkCueChange());
            this._renderCues();
        }

        // The browser cannot paint cues from our synthetic TextTrack objects.
        // Paint showing web cues above the native surface; hidden tracks remain
        // available to custom cuechange renderers without displaying twice.
        _renderCues() {
            const state = proxiedVideos.get(this._videoId);
            const video = state?.originalVideo;
            const cues = this._tracks.filter(track => track.mode === 'showing' && !track._nativeRendering)
                .flatMap(track => Array.from(track.activeCues));
            if (!state?._visible || !video?.parentElement || !cues.length) {
                this._clearCueOverlay();
                return;
            }
            if (!this._cueOverlay) {
                this._cueOverlay = document.createElement('div');
                this._cueOverlay.className = 'videoProxySubtitles';
                this._cueOverlay.style.cssText = 'position:fixed;pointer-events:none;overflow:hidden;z-index:2;';
            }
            if (this._cueOverlay.parentElement !== video.parentElement) video.parentElement.appendChild(this._cueOverlay);
            const rect = video.getBoundingClientRect();
            Object.assign(this._cueOverlay.style, {
                left: `${rect.left}px`, top: `${rect.top}px`, width: `${rect.width}px`, height: `${rect.height}px`,
                fontSize: `${Math.max(16, rect.height * 0.047)}px`,
            });
            if (cues.length === this._renderedCues.length && cues.every((cue, i) => cue === this._renderedCues[i])) return;
            this._renderedCues = cues;
            this._cueOverlay.replaceChildren();
            for (const cue of cues) {
                const line = document.createElement('div');
                line.style.cssText = 'position:absolute;left:2%;width:96%;text-align:center;white-space:pre-line;';
                line.style.textAlign = cue.align || 'center';
                if (typeof cue.line === 'number' && !cue.snapToLines) {
                    line.style.top = `${cue.line}%`;
                } else if (typeof cue.line === 'number' && cue.line >= 0) {
                    line.style.top = `${cue.line * 1.2}em`;
                } else {
                    line.style.bottom = `${typeof cue.line === 'number' ? Math.max(0, -cue.line - 1) * 1.2 + 0.5 : 0.5}em`;
                }
                const text = document.createElement('span');
                text.style.cssText = 'color:white;background:rgba(0,0,0,.8);padding:0 .15em;';
                // Jellyfin writes the user's text appearance into an inline ::cue rule.
                for (const style of document.querySelectorAll('style[id$="-cuestyle"]')) {
                    for (const rule of style.sheet?.cssRules || []) {
                        if (rule.selectorText?.includes('::cue')) text.style.cssText += rule.style.cssText;
                    }
                }
                if (typeof cue.getCueAsHTML === 'function') text.appendChild(cue.getCueAsHTML());
                else text.textContent = cue.text;
                line.appendChild(text);
                this._cueOverlay.appendChild(line);
            }
        }

        _clearCueOverlay() {
            this._cueOverlay?.remove();
            this._cueOverlay = null;
            this._renderedCues = [];
        }

        /**
         * Add a track created via video.addTextTrack().
         * Fires the addtrack event so the web client discovers it.
         */
        _addTrack(track) {
            this._tracks.push(track);
            const event = new Event('addtrack');
            event.track = track;
            this._listeners.addtrack.forEach(l => l(event));
            if (typeof this.onaddtrack === 'function') this.onaddtrack(event);
        }

        /**
         * Update the track list from ExoPlayer track info.
         * Preserves tracks created by the web client via addTextTrack().
         *
         * Mapping strategy: ExoPlayer only sees subtitle tracks embedded in the
         * container (IsExternal=false). Filter server streams to non-external
         * ones and map by position, so ExoPlayer group 0 → first embedded
         * server stream, group 1 → second embedded server stream, etc.
         *
         * @param {Array} trackInfos - Track info objects from ExoPlayer
         * @param {Array} [serverStreamIndices] - Server MediaStream.Index values
         * @param {Array} [serverSubtitleStreams] - Subtitle stream details from PlaybackInfo
         */
        _update(trackInfos, serverStreamIndices, serverSubtitleStreams) {
            const webTracks = this._tracks.filter(t => t._source === 'web');
            const oldTracks = this._tracks.filter(t => t._source !== 'web');

            // ExoPlayer only has tracks embedded in the container.
            // Filter to non-external server streams for positional mapping.
            const embeddedServerStreams = serverSubtitleStreams
                ? serverSubtitleStreams.filter(s => !s.isExternal)
                : [];

            const exoTracks = trackInfos.map((info, i) => {
                let serverStream = embeddedServerStreams[i];

                // Fallback: if embedded-stream filtering didn't produce enough
                // entries (e.g., IsExternal wasn't in the API response), try
                // the original positional mapping against all streams.
                if (!serverStream && serverStreamIndices && serverStreamIndices[i] !== undefined) {
                    const fallbackIdx = serverStreamIndices[i];
                    serverStream = serverSubtitleStreams
                        ? serverSubtitleStreams.find(s => s.index === fallbackIdx)
                        : undefined;
                }

                const serverIdx = serverStream ? serverStream.index : undefined;
                const enriched = Object.assign({}, info, {
                    serverStreamIndex: serverIdx,
                    _serverCodec: serverStream ? serverStream.codec : '',
                    _deliveryMethod: serverStream ? serverStream.deliveryMethod : '',
                });
                const existing = oldTracks.find(t => t._index === info.index && t.id === String(serverIdx ?? info.index));
                if (existing) {
                    existing._mode = info.isSelected ? 'showing' : 'disabled';
                    existing._nativeRendering = !!info.isSelected && existing._isNativeRenderable();
                    return existing;
                }
                const track = new ProxyTextTrack(this, enriched);
                track._nativeRendering = !!info.isSelected && track._isNativeRenderable();
                return track;
            });

            this._tracks = [...exoTracks, ...webTracks];

            // Fire addtrack events so the web client discovers embedded text
            // tracks (it listens for addtrack to detect native text tracks).
            exoTracks.filter(track => !oldTracks.includes(track)).forEach(track => {
                const event = new Event('addtrack');
                event.track = track;
                this._listeners.addtrack.forEach(l => l(event));
                if (typeof this.onaddtrack === 'function') this.onaddtrack(event);
            });

            this._dispatchChange();
        }

        _dispatchChange() {
            const event = new Event('change');
            this._listeners.change.forEach(l => l(event));
            if (typeof this.onchange === 'function') this.onchange(event);
            this._renderCues();
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
            this._nativeDescriptors = {};
            let prototype = originalVideo;
            while ((prototype = Object.getPrototypeOf(prototype))) {
                for (const key of Object.getOwnPropertyNames(prototype)) {
                    if (!this._nativeDescriptors[key]) this._nativeDescriptors[key] = Object.getOwnPropertyDescriptor(prototype, key);
                }
            }
            this._nativePause = originalVideo.pause.bind(originalVideo);
            this._nativeLoad = originalVideo.load.bind(originalVideo);
            this._nativeRemoveAttribute = originalVideo.removeAttribute.bind(originalVideo);
            this._playbackContext = null;
            this._subtitleSelectionInitialized = false;
            this._destroyed = false;
            this._error = null;
            this._buffering = false;
            this._playing = false;
            
            // Whether the native ExoPlayer has been created for this video
            this._nativeCreated = false;
            this._pendingPlayPromises = [];
            
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
            this._buffered = 0;
            this._seekable = false;

            // Track lists
            this._audioTrackList = new ProxyAudioTrackList(videoId);
            this._textTrackList = new ProxyTextTrackList(videoId);

            // When the web client downloads a subtitle that maps to an embedded
            // SRT track in ExoPlayer, this field holds the ExoPlayer group index
            // so the mode setter can redirect to native rendering.
            this._activeNativeSubtitleIndex = -1;
            
            // Seeking state: guards against stale position updates from native
            // during the window between JS bridge.seek() and ExoPlayer processing.
            this._seeking = false;
            this._seekStartTime = 0;

            // Post-seek guard: timestamp when the last seek completed (seeked=true
            // received). For a short window after this, reject position updates that
            // jump to 0 — ExoPlayer may transiently report position 0 before its
            // internal tracker stabilizes, especially for large files over slow networks.
            this._seekCompletedAt = 0;

            // Position tracking
            this._bounds = { x: 0, y: 0, width: 0, height: 0 };
            this._visible = true;
            this._fullscreen = false;

            // Native video intrinsic dimensions (from ExoPlayer)
            this._nativeVideoWidth = 0;
            this._nativeVideoHeight = 0;
            
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
        nativeGet(prop) {
            return this._nativeDescriptors[prop]?.get?.call(this.originalVideo);
        }

        nativeSet(prop, value) {
            this._nativeDescriptors[prop]?.set?.call(this.originalVideo, value);
        }

        settlePlayPromises(error = null) {
            const pending = this._pendingPlayPromises.splice(0);
            for (const promise of pending) {
                if (error) promise.reject(error);
                else promise.resolve();
            }
        }

        releaseNativePlayer() {
            this.settlePlayPromises(new DOMException('Playback source changed', 'AbortError'));
            this.stopObserving();
            this._textTrackList._clearCueOverlay();
            this.restoreTransparency();
            if (this._nativeCreated) bridge.onVideoElementDestroyed(this.videoId);
            this._nativeCreated = false;
            this.isProxied = false;
            proxiedVideos.delete(this.videoId);
        }

        setSource(value) {
            value = String(value);
            // Keep settings written before assigning the source.
            if (!this.isProxied && !this._destroyed) {
                this._volume = this.nativeGet('volume') ?? this._volume;
                this._muted = this.nativeGet('muted') ?? this._muted;
                this._playbackRate = this.nativeGet('playbackRate') ?? this._playbackRate;
            }
            this.releaseNativePlayer();
            this._destroyed = false;
            this.videoId = generateVideoId();
            this._src = value;
            this._playbackContext = contextForSource(value);
            this._subtitleSelectionInitialized = false;
            this._activeNativeSubtitleIndex = -1;
            this._audioTrackList = new ProxyAudioTrackList(this.videoId);
            this._textTrackList = new ProxyTextTrackList(this.videoId);
            this._currentTime = this._duration = this._readyState = this._buffered = 0;
            this._seekable = false;
            this._nativeVideoWidth = this._nativeVideoHeight = 0;
            this._bounds = { x: 0, y: 0, width: 0, height: 0 };
            this._paused = true;
            this._ended = this._seeking = this._buffering = this._playing = false;
            this._seekCompletedAt = 0;
            this._error = null;
            const nativePlaybackAllowed = !webPlaybackRequired && this._playbackContext?.nativeVideoSupported !== false;
            const resolveBlob = nativePlaybackAllowed && value.startsWith('blob:') && this._playbackContext && bridge.isEnabled();
            const direct = nativePlaybackAllowed && value && !value.startsWith('blob:') && !value.startsWith('data:') && bridge.shouldProxyUrl(value);
            if (!resolveBlob && !direct) {
                this.nativeSet('src', value);
                this.nativeSet('volume', this._volume);
                this.nativeSet('muted', this._muted);
                this.nativeSet('playbackRate', this._playbackRate);
                return;
            }
            this._nativePause();
            if (this.nativeGet('src')) {
                this._nativeRemoveAttribute('src');
                this._nativeLoad();
            }
            this.isProxied = true;
            proxiedVideos.set(this.videoId, this);
            this.ensureNativePlayer();
            if (resolveBlob) {
                bridge.resolveAndSetSource(this.videoId, this._playbackContext.itemId, this._playbackContext.mediaSourceId);
            } else {
                bridge.setSource(this.videoId, value);
            }
            bridge.setVolume(this.videoId, this._muted ? 0 : this._volume);
            bridge.setPlaybackRate(this.videoId, this._playbackRate);
            this.startObserving();
        }

        ensureNativePlayer() {
            if (this._nativeCreated) return;
            this._nativeCreated = true;
            console.log(`[VideoProxy] Creating native player for ${this.videoId}`);
            bridge.onVideoElementCreated(this.videoId);
        }

        /**
         * Make the video area transparent so the native SurfaceView
         * beneath the WebView can show through.
         * Sets all ancestor elements' backgrounds to transparent and
         * saves original values for later restoration.
         */
        makeTransparent() {
            if (this._transparent) return;
            this._transparent = true;
            const properties = ['opacity', 'background', 'background-color'];
            this._videoStyles = properties.map(name => ({ name,
                value: this.originalVideo.style.getPropertyValue(name),
                priority: this.originalVideo.style.getPropertyPriority(name) }));
            this.originalVideo.style.setProperty('opacity', '0', 'important');
            this.originalVideo.style.setProperty('background', 'transparent', 'important');
            this.originalVideo.style.setProperty('background-color', 'transparent', 'important');
            for (let element = this.originalVideo.parentElement; element; element = element.parentElement) {
                let saved = transparentAncestors.get(element);
                if (!saved) {
                    saved = { element, count: 0, styles: ['background', 'background-color'].map(name => ({ name,
                        value: element.style.getPropertyValue(name), priority: element.style.getPropertyPriority(name) })) };
                    transparentAncestors.set(element, saved);
                    element.style.setProperty('background', 'transparent', 'important');
                    element.style.setProperty('background-color', 'transparent', 'important');
                }
                saved.count++;
                this._savedStyles.push(saved);
            }
        }

        restoreTransparency() {
            if (!this._transparent) return;
            const restore = (element, styles) => styles.forEach(({ name, value, priority }) => {
                if (value) element.style.setProperty(name, value, priority);
                else element.style.removeProperty(name);
            });
            restore(this.originalVideo, this._videoStyles);
            for (const saved of this._savedStyles) {
                if (--saved.count === 0) {
                    restore(saved.element, saved.styles);
                    transparentAncestors.delete(saved.element);
                }
            }
            this._savedStyles = [];
            this._transparent = false;
        }

        /**
         * Start observing the video element for position/size changes
         */
        startObserving() {
            this.stopObserving();
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
                    this._textTrackList._renderCues();
                }
            }, { threshold: [0, 0.1, 0.5, 1.0] });
            this._intersectionObserver.observe(this.originalVideo);

            // Listen for scroll events
            window.addEventListener('scroll', this._boundUpdatePosition, { passive: true });
            window.addEventListener('resize', this._boundUpdatePosition);
            document.addEventListener('fullscreenchange', this._boundUpdatePosition);
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
            window.removeEventListener('resize', this._boundUpdatePosition);
            document.removeEventListener('fullscreenchange', this._boundUpdatePosition);
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
                height: rect.height,
                viewportWidth: window.innerWidth,
                objectFit: getComputedStyle(this.originalVideo).objectFit || 'contain'
            };

            // Only update if bounds changed
            if (this._bounds.x !== newBounds.x ||
                this._bounds.y !== newBounds.y ||
                this._bounds.width !== newBounds.width ||
                this._bounds.height !== newBounds.height ||
                this._bounds.viewportWidth !== newBounds.viewportWidth ||
                this._bounds.objectFit !== newBounds.objectFit) {
                this._bounds = newBounds;
                bridge.updateBounds(this.videoId, JSON.stringify(newBounds));
            }
            this._textTrackList._renderCues();
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
            if (this._destroyed) return;
            this.releaseNativePlayer();
            this._destroyed = true;
            this._paused = true;
        }
    }

    /**
     * Create property descriptors for video element proxy
     */
    function createPropertyDescriptors(state) {
        return {
            // Source property
            src: {
                get() { return state.isProxied ? state._src : state.nativeGet('src'); },
                set(value) { state.setSource(value); }
            },
            currentSrc: {
                get() { return state.isProxied ? state._src : state.nativeGet('currentSrc'); }
            },
            error: {
                get() { return state.isProxied ? state._error : state.nativeGet('error'); }
            },

            // Current time
            currentTime: {
                get() { return state.isProxied ? state._currentTime : state.nativeGet('currentTime'); },
                set(value) {
                    state._currentTime = value;
                    if (state.isProxied) {
                        state._seeking = true;
                        state._seekStartTime = Date.now();
                        state.dispatchEvent('seeking');
                        bridge.seek(state.videoId, Math.floor(value * 1000));
                    }
                    if (!state.isProxied) state.nativeSet('currentTime', value);
                    state._textTrackList._checkCueChanges();
                }
            },

            // Seeking state (read-only)
            seeking: {
                get() { return state.isProxied ? state._seeking : state.nativeGet('seeking'); }
            },

            // Duration (read-only from JS perspective)
            duration: {
                get() { return state.isProxied ? state._duration : state.nativeGet('duration'); },
                set(value) { state._duration = value; }
            },

            // Paused state
            paused: {
                get() { return state.isProxied ? state._paused : state.nativeGet('paused'); },
                set(value) { state._paused = value; }
            },

            // Ended state
            ended: {
                get() { return state.isProxied ? state._ended : state.nativeGet('ended'); },
                set(value) { state._ended = value; }
            },

            // Volume
            volume: {
                get() { return state.isProxied ? state._volume : state.nativeGet('volume'); },
                set(value) {
                    state._volume = Math.max(0, Math.min(1, value));
                    if (state.isProxied) {
                        bridge.setVolume(state.videoId, state._muted ? 0 : state._volume);
                        state.dispatchEvent('volumechange');
                    } else {
                        state.nativeSet('volume', state._volume);
                    }
                }
            },

            // Muted
            muted: {
                get() { return state.isProxied ? state._muted : state.nativeGet('muted'); },
                set(value) {
                    state._muted = value;
                    if (state.isProxied) {
                        bridge.setVolume(state.videoId, value ? 0 : state._volume);
                        state.dispatchEvent('volumechange');
                    } else {
                        state.nativeSet('muted', value);
                    }
                }
            },

            // Playback rate
            playbackRate: {
                get() { return state.isProxied ? state._playbackRate : state.nativeGet('playbackRate'); },
                set(value) {
                    state._playbackRate = state.isProxied ? Math.max(0.25, Math.min(2, value)) : value;
                    if (state.isProxied) {
                        bridge.setPlaybackRate(state.videoId, state._playbackRate);
                        state.dispatchEvent('ratechange');
                    } else {
                        state.nativeSet('playbackRate', value);
                    }
                }
            },

            // Ready state
            readyState: {
                get() { return state.isProxied ? state._readyState : state.nativeGet('readyState'); },
                set(value) { state._readyState = value; }
            },

            // Video dimensions (intrinsic video size from ExoPlayer)
            videoWidth: {
                get() { return state.isProxied ? state._nativeVideoWidth : state.nativeGet('videoWidth'); }
            },
            videoHeight: {
                get() { return state.isProxied ? state._nativeVideoHeight : state.nativeGet('videoHeight'); }
            },

            // Advertise native seeking so the web client uses currentTime instead
            // of reloading the source and destroying the SurfaceView on every seek.
            seekable: {
                get() {
                    if (!state.isProxied) return state.nativeGet('seekable');
                    const end = state._seekable && Number.isFinite(state._duration) ? state._duration : 0;
                    return {
                        length: end > 0 ? 1 : 0,
                        start(index) { if (index !== 0 || end <= 0) throw new DOMException('Invalid range', 'IndexSizeError'); return 0; },
                        end(index) { if (index !== 0 || end <= 0) throw new DOMException('Invalid range', 'IndexSizeError'); return end; }
                    };
                }
            },

            // Buffered range reported by ExoPlayer
            buffered: {
                get() {
                    if (!state.isProxied) return state.nativeGet('buffered');
                    const end = state._buffered;
                    return {
                        length: end > 0 ? 1 : 0,
                        start(index) { if (index !== 0 || end <= 0) throw new DOMException('Invalid range', 'IndexSizeError'); return 0; },
                        end(index) { if (index !== 0 || end <= 0) throw new DOMException('Invalid range', 'IndexSizeError'); return end; }
                    };
                }
            },

            // Audio tracks - proxied from ExoPlayer
            audioTracks: {
                get() { return state.isProxied ? state._audioTrackList : state.nativeGet('audioTracks'); }
            },

            // Text tracks - proxied from ExoPlayer
            textTracks: {
                get() { return state.isProxied ? state._textTrackList : state.nativeGet('textTracks'); }
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
        const originalSetAttribute = video.setAttribute.bind(video);
        const originalRemoveAttribute = video.removeAttribute.bind(video);

        video.setAttribute = function(name, value) {
            if (String(name).toLowerCase() === 'src') state.setSource(value);
            else originalSetAttribute(name, value);
        };
        video.removeAttribute = function(name) {
            if (String(name).toLowerCase() === 'src') state.setSource('');
            originalRemoveAttribute(name);
        };

        video.play = function() {
            if (state.isProxied) {
                // Match HTMLMediaElement.play(): callers may restore pause or
                // tracks only after playback has actually started. Returning an
                // already-resolved promise skips the web player's startup seek.
                const started = state._playing && !state._paused;
                const promise = started ? Promise.resolve() : new Promise((resolve, reject) => {
                    state._pendingPlayPromises.push({ resolve, reject });
                });
                if (state._paused) {
                    state._paused = false;
                    state.dispatchEvent('play');
                }
                state._ended = false;
                bridge.play(state.videoId);
                return promise;
            }
            return originalPlay();
        };

        video.pause = function() {
            if (state.isProxied) {
                state.settlePlayPromises(new DOMException('Playback was paused', 'AbortError'));
                if (!state._paused) {
                    state._paused = true;
                    state._playing = false;
                    state.dispatchEvent('pause');
                }
                bridge.pause(state.videoId);
                return;
            }
            return originalPause();
        };

        video.load = function() {
            if (state.isProxied) {
                state.setSource(state._src);
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

        // Intercept addTextTrack so tracks created by the web client
        // (e.g., for external subtitles) go through our ProxyTextTrackList.
        const originalAddTextTrack = video.addTextTrack?.bind(video);
        video.addTextTrack = function(kind, label, language) {
            if (state.isProxied) {
                const trackInfo = {
                    index: state._textTrackList._tracks.length,
                    label: label || '',
                    language: language || '',
                    codec: '',
                    channelCount: 0,
                    isDefault: false,
                    isForced: kind === 'forced',
                    isSelected: false,
                    _source: 'web',
                };
                const track = new ProxyTextTrack(state._textTrackList, trackInfo);
                track.kind = kind || 'subtitles';
                state._textTrackList._addTrack(track);
                console.log(`[VideoProxy] addTextTrack: kind=${kind} label=${label} lang=${language}` +
                    ` (nativeOverride=${state._activeNativeSubtitleIndex})`);
                return track;
            }
            return originalAddTextTrack ? originalAddTextTrack(kind, label, language) : null;
        };
    }

    /**
     * Proxy an existing video element
     */
    function proxyVideoElement(video) {
        // Skip if already proxied
        if (video._videoProxyState) {
            const state = video._videoProxyState;
            if (state._destroyed) state.setSource(state._src);
            return video;
        }

        const videoId = generateVideoId();
        const state = new VideoProxyState(videoId, video);
        
        // Store state on video element
        video._videoProxyState = state;
        proxiedVideos.set(videoId, state);

        const existingSrc = state.nativeGet('src');

        // Apply property descriptors
        const descriptors = createPropertyDescriptors(state);
        for (const [prop, descriptor] of Object.entries(descriptors)) {
            try {
                Object.defineProperty(video, prop, { ...descriptor, configurable: true });
            } catch (e) {
                console.warn(`[VideoProxy] Failed to define property ${prop}:`, e);
            }
        }

        // Wrap methods
        wrapVideoMethods(video, state);

        // Handle removal from DOM
        const originalRemove = video.remove.bind(video);
        video.remove = function() {
            const result = originalRemove();
            if (!video.isConnected) state.destroy();
            return result;
        };

        if (existingSrc) state.setSource(existingSrc);
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
            // A move within the document is not a player destruction.
            for (const node of mutation.removedNodes) {
                if (node.nodeType === Node.ELEMENT_NODE && !node.isConnected) {
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
            if (!proxyState || !proxyState.isProxied) return;
            // CSS fit modes can change without resizing the video element.
            proxyState._boundUpdatePosition();
            const previousTime = proxyState._currentTime;
            let seekCompleted = false;
            if (state.seekable !== undefined) proxyState._seekable = state.seekable;
            if (state.currentTime !== undefined) {
                if (proxyState._seeking) {
                    if (state.seeked) {
                        proxyState._seeking = false;
                        proxyState._seekCompletedAt = Date.now();
                        seekCompleted = true;
                    }
                } else {
                    const time = state.currentTime / 1000;
                    const rejectZero = proxyState._seekCompletedAt > 0 &&
                        Date.now() - proxyState._seekCompletedAt < 1500 && time < 1 && previousTime > 5;
                    if (!rejectZero) proxyState._currentTime = time;
                }
            }
            if (state.duration !== undefined && proxyState._duration !== state.duration / 1000) {
                proxyState._duration = state.duration / 1000;
                proxyState.dispatchEvent('durationchange');
            }
            if (state.buffered !== undefined && proxyState._buffered !== state.buffered / 1000) {
                proxyState._buffered = state.buffered / 1000;
                proxyState.dispatchEvent('progress');
            }
            if (state.paused !== undefined && proxyState._paused !== state.paused) {
                proxyState._paused = state.paused;
                proxyState.dispatchEvent(state.paused ? 'pause' : 'play');
            }
            if (state.ended && !proxyState._ended) {
                proxyState._ended = true;
                proxyState._paused = true;
                proxyState.dispatchEvent('ended');
            }
            if (state.readyState !== undefined) {
                const previousReadyState = proxyState._readyState;
                proxyState._readyState = state.readyState;
                if (proxyState._seeking && previousReadyState <= 2 && state.readyState >= 4) {
                    proxyState._seeking = false;
                    proxyState._seekCompletedAt = Date.now();
                    seekCompleted = true;
                }
                if (previousReadyState < 1 && state.readyState >= 1) proxyState.dispatchEvent('loadedmetadata');
                if (previousReadyState < 2 && state.readyState >= 2) proxyState.dispatchEvent('loadeddata');
                if (previousReadyState < 3 && state.readyState >= 3) proxyState.dispatchEvent('canplay');
                if (previousReadyState < 4 && state.readyState >= 4) proxyState.dispatchEvent('canplaythrough');
            }
            if (seekCompleted) proxyState.dispatchEvent('seeked');
            if (seekCompleted || previousTime !== proxyState._currentTime) {
                proxyState.dispatchEvent('timeupdate');
                proxyState._textTrackList._checkCueChanges();
            }
            const playing = !proxyState._paused && !proxyState._ended && !proxyState._buffering && proxyState._readyState >= 3;
            if (playing && !proxyState._playing) {
                proxyState.dispatchEvent('playing');
                proxyState.settlePlayPromises();
            }
            proxyState._playing = playing;
        },

        onBuffering(videoId, isBuffering) {
            const proxyState = proxiedVideos.get(videoId);
            if (!proxyState || !proxyState.isProxied) return;
            if (isBuffering && !proxyState._buffering && !proxyState._paused) proxyState.dispatchEvent('waiting');
            proxyState._buffering = isBuffering;
            if (isBuffering) proxyState._playing = false;
        },

        /**
         * Called when an error occurs
         */
        onError(videoId, errorCode, errorMessage) {
            const proxyState = proxiedVideos.get(videoId);
            if (!proxyState) return;

            proxyState._error = Object.freeze({ code: errorCode, message: errorMessage });
            proxyState.settlePlayPromises(new DOMException(errorMessage, 'NotSupportedError'));
            proxyState._playing = false;
            proxyState._seeking = false;
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
         * Called when ExoPlayer has rendered its first video frame.
         * This is the signal to make the web content transparent so the
         * native video can show through, ensuring the episode backdrop
         * image stays visible until there's actual video content to display.
         * @param {string} videoId - The video element ID
         */
        onFirstFrameRendered(videoId) {
            const proxyState = proxiedVideos.get(videoId);
            if (!proxyState) return;

            if (proxyState.isProxied && proxyState._savedStyles.length === 0) {
                console.log(`[VideoProxy] First frame rendered for ${videoId}, making transparent`);
                proxyState.makeTransparent();
            }
        },

        /**
         * Called when the native video size changes (from ExoPlayer).
         * Updates the proxied videoWidth and videoHeight properties.
         * @param {string} videoId - The video element ID
         * @param {number} width - Video intrinsic width in pixels
         * @param {number} height - Video intrinsic height in pixels
         * @param {number} pixelWidthHeightRatio - Pixel aspect ratio
         */
        onVideoSizeChanged(videoId, width, height, pixelWidthHeightRatio) {
            const proxyState = proxiedVideos.get(videoId);
            if (!proxyState) return;

            // Apply pixel aspect ratio to get display width
            proxyState._nativeVideoWidth = Math.round(width * (pixelWidthHeightRatio || 1));
            proxyState._nativeVideoHeight = height;
            proxyState.dispatchEvent('resize');
            console.log(`[VideoProxy] Video size for ${videoId}: ${proxyState._nativeVideoWidth}x${proxyState._nativeVideoHeight}`);
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
                    // Pass server stream indices so ProxyAudioTrack.id matches
                    // the server's MediaStream.Index (used by the web client's
                    // setAudioStreamIndex for track matching).
                    const serverAudioIndices = proxyState._playbackContext?.audioStreamIndices;
                    proxyState._audioTrackList._update(tracks.audioTracks, serverAudioIndices);
                    console.log(`[VideoProxy] Updated ${tracks.audioTracks.length} audio tracks for ${videoId}` +
                        (serverAudioIndices ? ` (server indices: [${serverAudioIndices.join(',')}])` : ''));
                }

                if (tracks.subtitleTracks && proxyState._textTrackList) {
                    const serverSubtitleIndices = proxyState._playbackContext?.subtitleStreamIndices;
                    const serverSubtitleStreams = proxyState._playbackContext?.subtitleStreams;
                    proxyState._textTrackList._update(tracks.subtitleTracks, serverSubtitleIndices, serverSubtitleStreams);

                    const embeddedCount = serverSubtitleStreams
                        ? serverSubtitleStreams.filter(s => !s.isExternal).length : 0;
                    const externalCount = serverSubtitleStreams
                        ? serverSubtitleStreams.filter(s => s.isExternal).length : 0;
                    console.log(`[VideoProxy] Updated ${tracks.subtitleTracks.length} subtitle tracks for ${videoId}` +
                        ` (server: ${embeddedCount} embedded + ${externalCount} external` +
                        (serverSubtitleIndices ? `, indices: [${serverSubtitleIndices.join(',')}]` : '') + ')');

                    // Log individual track mappings for debugging
                    proxyState._textTrackList._tracks.forEach(t => {
                        if (t._source !== 'web') {
                            console.log(`[VideoProxy]   Track ${t._index}: id=${t.id} lang=${t.language}` +
                                ` serverCodec=${t._serverCodec} delivery=${t._deliveryMethod}`);
                        }
                    });

                    autoEnableSubtitleTrack(proxyState);
                }
            } catch (e) {
                console.error('[VideoProxy] Failed to parse tracks JSON:', e);
            }
        }
    };

    // ─── Define audioTracks on HTMLMediaElement.prototype ───
    //
    // Some web client code checks browser capabilities at the prototype level
    // (e.g., 'audioTracks' in HTMLMediaElement.prototype) to determine if
    // native audio track switching is available. Chrome doesn't have this
    // property natively, so we add it. The getter delegates to the proxy
    // state if available, ensuring both feature detection and runtime access
    // work correctly.
    if (!('audioTracks' in HTMLMediaElement.prototype)) {
        Object.defineProperty(HTMLMediaElement.prototype, 'audioTracks', {
            get() {
                const state = this._videoProxyState;
                if (state) {
                    return state._audioTrackList;
                }
                return undefined;
            },
            configurable: true,
            enumerable: true,
        });
        console.log('[VideoProxy] Defined audioTracks on HTMLMediaElement.prototype');
    }

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
     * Inject the ExoPlayer debug toggle into Jellyfin's video settings action sheet.
     * Identify the menu by its playback-data item, independent of the UI language.
     */
    (function injectDebugMenuItem() {
        /**
         * Create a menu item by cloning an existing item's structure,
         * so it inherits the same classes, icons layout and CSS.
         * Falls back to a plain button if no template item is available.
         * @param {Element} container - The menu container to find a template item from
         * @returns {Element} The menu item button
         */
        function createMenuItem(container) {
            const template = container.querySelector('button');
            const language = (document.documentElement.lang || navigator.language || '').toLowerCase();
            const label = language.startsWith('zh')
                ? (/^zh-(tw|hk|mo|hant)(-|$)/.test(language) ? 'ExoPlayer 偵錯' : 'ExoPlayer 调试')
                : 'ExoPlayer Debug';
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
                btn.removeAttribute('autofocus');
                btn.removeAttribute('onclick');
                // Do not inherit values such as "Auto" or a selected quality.
                btn.querySelectorAll('.listItemAside, .actionSheetItemAsideText, .secondary')
                    .forEach(element => element.remove());
                // Replace all text content while preserving icon structure
                const textEl = btn.querySelector('.actionSheetItemText') ||
                    btn.querySelector('[class*="ItemText"]') ||
                    btn.lastElementChild ||
                    btn;
                textEl.textContent = label;
                // Update icon if present
                const iconEl = btn.querySelector('.material-icons, [class*="Icon"]');
                if (iconEl && iconEl !== textEl) {
                    iconEl.className = 'actionsheetMenuItemIcon listItemIcon listItemIcon-transparent material-icons bug_report';
                    iconEl.textContent = '';
                    iconEl.style.removeProperty('visibility');
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
                btn.textContent = label;
            }

            // Replace all event listeners by cloning without listeners
            const clean = btn.cloneNode(true);
            clean.addEventListener('click', function(e) {
                e.preventDefault();
                e.stopPropagation();
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
         * Try to inject our menu item into the video settings action sheet.
         * @param {Element} container - The action sheet element
         */
        function tryInjectIntoMenu(container) {
            // Skip if already injected
            if (container.querySelector('.btnVideoProxyDebug')) return;
            // Only expose native debug info while an ExoPlayer proxy is active.
            if (!Array.from(proxiedVideos.values()).some(state => state.isProxied)) return;

            const itemsContainer = container.querySelector('.actionSheetScroller');

            if (!itemsContainer) return;

            // The video settings menu always includes PlaybackData (id="stats"),
            // even when quality/transcoding is unavailable. Its submenus and
            // unrelated dialogs do not contain this item.
            if (!itemsContainer.querySelector('.actionSheetMenuItem[data-id="stats"]')) return;

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

        // Observe both new action sheets and items populated after sheet creation.
        const menuObserver = new MutationObserver((mutations) => {
            const menus = new Set();
            for (const mutation of mutations) {
                const currentMenu = mutation.target.closest?.('.actionSheet');
                if (currentMenu) menus.add(currentMenu);

                for (const node of mutation.addedNodes) {
                    if (node.nodeType !== Node.ELEMENT_NODE) continue;
                    if (node.matches('.actionSheet')) menus.add(node);
                    node.querySelectorAll('.actionSheet').forEach(menu => menus.add(menu));
                }
            }
            menus.forEach(menu => {
                if (menu.isConnected) tryInjectIntoMenu(menu);
            });
        });

        menuObserver.observe(document.documentElement, {
            childList: true,
            subtree: true
        });

        console.log('[VideoProxy] Debug menu injection initialized');
    })();

    console.log('[VideoProxy] Video element proxy initialized');
})();

// Select the decoder before device-profile negotiation, including the first
// episode, playlist transitions and series with a saved enhancement profile.
export class VideoProxyPlaybackPlugin {
    constructor() {
        this.name = 'Video proxy playback';
        this.id = 'videoproxyplayback';
        this.type = 'preplayintercept';
        this.priority = -1;
    }

    async intercept(options) {
        if (options.mediaType === 'Video') {
            window.VideoProxy?.preparePlayback(options.item);
        }
    }
}

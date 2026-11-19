export class NavigationPlugin {
    constructor({ playbackManager }) {
        window['NavigationHelper'] = this;

        this.playbackManager = playbackManager;
    }

    goBack() {
        // The main drawer is outside the router history. Dismiss it through its scrim.
        const drawerScrim = document.querySelector('.tmla-mask.backdrop:not(.hide)');
        if (drawerScrim) {
            drawerScrim.click();
            return;
        }

        var appRouter = window['Emby']['Page'];
        if (appRouter.canGoBack()) {
            appRouter.back();
        } else {
            window['NativeInterface'].exitApp();
        }
    }
}

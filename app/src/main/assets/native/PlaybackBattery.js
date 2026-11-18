(() => {
    const bridge = window.NativeInterface;
    if (typeof bridge?.getBatteryLevel !== 'function') return;

    const style = document.createElement('style');
    style.textContent = `
        .skinHeader.osdHeader #headerClock.android-osd-clock-with-battery {
            display: inline-flex;
            align-items: center;
            max-width: none;
            gap: .55em;
            direction: ltr;
            white-space: nowrap;
        }

        .android-osd-battery {
            display: inline-flex;
            align-items: center;
            gap: .4em;
            font-variant-numeric: tabular-nums;
        }

        .android-osd-battery-icon {
            position: relative;
            box-sizing: border-box;
            width: 1.15em;
            height: .7em;
            padding: 1px;
            border: 1px solid currentColor;
            border-radius: 2px;
            background: linear-gradient(to right, currentColor var(--battery-level), transparent 0) content-box;
        }

        .android-osd-battery-icon::after {
            content: '';
            position: absolute;
            top: 25%;
            bottom: 25%;
            left: calc(100% + 1px);
            width: 2px;
            background: currentColor;
        }
    `;
    document.head.appendChild(style);

    const battery = document.createElement('span');
    battery.className = 'android-osd-battery';
    const icon = document.createElement('span');
    icon.className = 'android-osd-battery-icon';
    icon.setAttribute('aria-hidden', 'true');
    const percentage = document.createElement('span');
    battery.append(icon, percentage);
    const percentFormat = new Intl.NumberFormat(undefined, { style: 'percent' });

    let header = null;
    let clock = null;
    let refreshTimer;
    const headerObserver = new MutationObserver(syncClock);

    function refreshBattery() {
        const level = bridge.getBatteryLevel();
        const available = Number.isInteger(level) && level >= 0 && level <= 100;
        clock.classList.toggle('android-osd-clock-with-battery', available);
        if (!available) {
            battery.remove();
            return;
        }

        const text = percentFormat.format(level / 100);
        if (percentage.textContent !== text) {
            icon.style.setProperty('--battery-level', `${level}%`);
            percentage.textContent = text;
        }
        if (battery.parentNode !== clock) clock.prepend(battery);
    }

    function stopRefreshing() {
        if (refreshTimer === undefined) return;
        clearInterval(refreshTimer);
        refreshTimer = undefined;
    }

    function syncClock() {
        const nextClock = document.getElementById('headerClock');
        const nextHeader = nextClock?.closest('.skinHeader') ?? null;
        if (header !== nextHeader) {
            headerObserver.disconnect();
            header = nextHeader;
            if (header) headerObserver.observe(header, { attributeFilter: ['class'] });
        }

        const osdClock = header?.classList.contains('osdHeader') ? nextClock : null;
        if (clock !== osdClock) {
            stopRefreshing();
            clock?.classList.remove('android-osd-clock-with-battery');
            battery.remove();
            clock = osdClock;
        }

        if (clock && !document.hidden && !header.classList.contains('osdHeader-hidden')) {
            if (refreshTimer === undefined) {
                refreshBattery();
                refreshTimer = setInterval(refreshBattery, 30_000);
            }
        } else {
            stopRefreshing();
        }
    }

    const pageObserver = new MutationObserver(syncClock);
    pageObserver.observe(document.body, { childList: true, subtree: true });
    document.addEventListener('visibilitychange', syncClock);
    syncClock();
})();

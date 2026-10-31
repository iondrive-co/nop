(() => {
    if (location.protocol !== "https:" || location.hostname !== "app.slack.com") return;
    if (window.__nopSlackLayout) {
        window.__nopSlackLayout.refresh();
        return;
    }

    const scope = 'html[data-nop-slack-layout="channels-right"]';
    const style = document.createElement("style");
    style.id = "nop-slack-layout";
    const themeStyle = document.createElement("style");
    themeStyle.id = "nop-slack-theme";
    style.textContent = `
        ${scope} { color-scheme: var(--nop-color-scheme); }
        ${scope} body { background: var(--nop-background) !important; color: var(--nop-foreground) !important; }
        ${scope} .p-ia4_top_nav, ${scope} .p-tab_rail, ${scope} .p-control_strip { display: none !important; }
        ${scope} .p-client_workspace_wrapper {
            height: 100% !important;
            grid-template-columns: minmax(0, 1fr) !important;
            grid-template-rows: minmax(0, 1fr) auto !important;
            grid-template-areas: "p-client-workspace" "p-client__banners" !important;
        }
        ${scope} .p-client_workspace { padding: 0 !important; margin: 0 !important; grid-template-columns: minmax(0, 1fr) !important; }
        ${scope} .p-client_workspace__tabpanel[data-nop-channels-right] {
            grid-template-columns: minmax(0, 1fr) min(var(--nop-channel-width, 216px), 45%) !important;
            grid-template-areas: "p-view_contents--primary p-view_contents--sidebar" !important;
        }
        ${scope} .p-ia4_client__resizer--sidebar {
            left: auto !important;
            right: calc(min(var(--nop-channel-width, 216px), 45%) - 4px) !important;
            cursor: col-resize !important;
        }
        ${scope} .p-view_contents { min-width: 0 !important; border-radius: 0 !important; }
        ${scope} .p-view_contents--sidebar { border-left: 1px solid var(--nop-border) !important; }
        ${scope} :is(.p-ia4_client, .p-client_workspace, .p-client_workspace__layout, .p-client_workspace__tabpanel,
            .p-view_contents--primary, .p-workspace__primary_view_body, .p-message_pane,
            .c-virtual_list__scroll_container, .c-message_kit__background, .c-message_kit__message) {
            background: var(--nop-background) !important;
            color: var(--nop-foreground) !important;
        }
        ${scope} :is(.p-view_contents--sidebar, .p-channel_sidebar, .p-channel_sidebar__list,
            .p-ia4_home_header, .p-channel_sidebar__section_heading) {
            background: var(--nop-panel) !important;
            color: var(--nop-foreground) !important;
        }
        ${scope} :is(.p-workspace__input, .p-message_input, .p-message_input_field, .ql-toolbar, .ql-container,
            .p-message_pane_input_inner_main, input, textarea, [contenteditable="true"]) {
            background: var(--nop-background) !important;
            color: var(--nop-foreground) !important;
            border-color: var(--nop-border) !important;
        }
        ${scope} .p-channel_sidebar__link { color: var(--nop-foreground) !important; }
        ${scope} .p-channel_sidebar__link:hover { background: var(--nop-hover) !important; }
        ${scope} .p-channel_sidebar__link--selected { background: var(--nop-selection) !important; }
        ${scope} .p-ia4_channel_list > .p-autoclog__hook:has(> [data-qa="upgrades-kit-upgrade-button"]),
        ${scope} .p-sidebar_text_filter_input_header { display: none !important; }
        ${scope} :is(a, .c-link) { color: var(--nop-accent) !important; }
        ${scope} .c-button--primary { background: var(--nop-accent) !important; color: white !important; }
        ${scope} :is(.p-view_header, .p-ia4_view_header, .p-message_pane__foreword, .p-workspace__primary_view_footer,
            .p-workspace__primary_view_footer--float) { background-color: var(--nop-background) !important; }
        ${scope} :is(.c-menu, .c-popover__content, .c-dialog, .c-fullscreen_modal, .c-select_options_list,
            .p-client__banners, .p-ia_banner) {
            background: var(--nop-panel) !important;
            color: var(--nop-foreground) !important;
            border-color: var(--nop-border) !important;
        }
        ${scope} .c-menu_item__button:hover { background: var(--nop-hover) !important; }
    `;

    const defaults = { dark: true, background: "#1e1f22", panel: "#2b2d30", foreground: "#dfe1e5", muted: "#9da0a8",
        border: "#43454a", hover: "#35373b", selection: "#2e436e", accent: "#6b9bfa" };
    let appearance = defaults;
    let pending = 0;
    let current = null;
    let panel = null;
    let themed = false;
    let drag = null;
    let channelWidth = 216;
    const observer = new MutationObserver(schedule);

    function rgb(hex) {
        return [1, 3, 5].map(index => parseInt(hex.slice(index, index + 2), 16)).join(", ");
    }

    function setTheme(next) {
        appearance = { ...defaults, ...next };
        const root = document.documentElement;
        for (const key of Object.keys(defaults)) {
            if (key === "dark") continue;
            const color = /^#[0-9a-f]{6}$/i.test(appearance[key]) ? appearance[key] : defaults[key];
            root.style.setProperty(`--nop-${key}`, color);
            root.style.setProperty(`--nop-${key}-rgb`, rgb(color));
        }
        root.style.setProperty("--nop-color-scheme", appearance.dark ? "dark" : "light");
        schedule();
    }

    function themeTokens(sidebar) {
        const variables = new Set();
        for (const element of [document.documentElement, document.body, sidebar,
            ...[...document.querySelectorAll('[class*="sk-client-theme"]')].slice(0, 6)]) {
            if (element) for (const name of getComputedStyle(element)) variables.add(name);
        }
        const rules = [];
        for (const name of variables) {
            let value = null;
            if (/^--dt_color-(theme-)?(content|text)-/.test(name)) {
                value = /-(sec|ter)$/.test(name) ? "var(--nop-muted)" : "var(--nop-foreground)";
            } else if (/^--dt_color-(theme-)?(otl|border)-/.test(name)) {
                value = "var(--nop-border)";
            } else if (/^--dt_color-(theme-)?(base|surf|surface|background)-/.test(name)) {
                value = /-(hover|pressed)$/.test(name) ? "var(--nop-hover)" : "var(--nop-panel)";
            } else if (name === "--sk_primary_background") value = "var(--nop-background-rgb)";
            else if (name === "--sk_primary_foreground") value = "var(--nop-foreground-rgb)";
            else if (name === "--sk_secondary_foreground") value = "var(--nop-muted-rgb)";
            else if (/^--sk_foreground_/.test(name)) value = name.endsWith("_solid") ? "var(--nop-border-rgb)" : "var(--nop-foreground-rgb)";
            else if (/^--sk_(highlight|brand_foreground|focused)/.test(name)) value = "var(--nop-accent-rgb)";
            else if (name === "--p-team_sidebar__nav-bg") value = "var(--nop-panel)";
            else if (name.startsWith("--p-team_sidebar__text-color")) value = "var(--nop-foreground)";
            else if (name.startsWith("--p-team_sidebar__badge")) value = "var(--nop-selection)";
            if (value) rules.push(`${name}: ${value} !important;`);
        }
        themeStyle.textContent = `${scope}, ${scope} :is(body, [class*="sk-client-theme"], .p-view_contents, .p-workspace__input) { ${rules.join("\n")} }`;
        if (!themeStyle.isConnected) document.head.append(themeStyle);
        themed = true;
    }

    function metadata() {
        let config = {};
        try { config = JSON.parse(localStorage.getItem("localConfig_v2") || "{}"); } catch (_) {}
        const teams = config.teams && typeof config.teams === "object" ? config.teams : {};
        const workspaces = Object.values(teams).slice(0, 128)
            .filter(team => team && /^T[A-Z0-9]{1,31}$/.test(team.id) && typeof team.name === "string")
            .map(team => ({ id: team.id, name: team.name.slice(0, 120) }));
        const id = location.pathname.match(/^\/client\/(T[A-Z0-9]{1,31})(?:\/|$)/)?.[1];
        const heading = document.querySelector(".p-ia4_home_header_menu__team_name")?.textContent?.trim();
        const name = heading || workspaces.find(team => team.id === id)?.name;
        // Slack's title carries notification counts even when the conversation isn't visible.
        const titleCount = Number(document.title.match(/^\s*\((\d+)\)/)?.[1] || 0);
        const count = element => parseInt(element.textContent.trim(), 10) || 0;
        const channels = [...document.querySelectorAll('.p-channel_sidebar__channel .p-channel_sidebar__badge')]
            .reduce((total, element) => total + count(element), 0);
        const badges = [...document.querySelectorAll('.p-tab_rail__badge, [data-qa="mention_badge"]')];
        const mentions = Math.max(titleCount, channels, ...badges.map(count));
        const unread = mentions > 0 || /^\s*\*/.test(document.title) ||
            !!document.querySelector('.p-channel_sidebar__link--unread, .p-channel_sidebar__channel--unread:not(.p-channel_sidebar__channel--muted), .p-channel_sidebar__section_heading--unreads');
        return { current: id && name ? { id, name: name.slice(0, 120) } : null, workspaces,
            attention: { unread, mentions: Math.min(9999, mentions) } };
    }

    // Keep Slack's sidebar rows, section membership, heights and offsets native. Its
    // virtualizer mounts only a window of rows, and headings may be outside that
    // window. Hiding inferred sections or reflowing rows breaks expansion/scrolling.

    function storageKey() { return `nop.channelWidth.${metadata().current?.id || "default"}`; }

    function clear() {
        panel?.removeAttribute("data-nop-channels-right");
        document.documentElement.removeAttribute("data-nop-slack-layout");
        current = null;
        panel = null;
    }

    function refresh() {
        pending = 0;
        const wrapper = /^\/client(?:\/|$)/.test(location.pathname)
            ? [...document.querySelectorAll(".p-client_workspace_wrapper")].find(element =>
                element.getBoundingClientRect().width > 0 && element.querySelector(".p-client_workspace"))
            : null;
        if (!wrapper) { clear(); return; }
        const sidebar = wrapper.querySelector(".p-view_contents--sidebar");
        const tabpanel = sidebar?.closest(".p-client_workspace__tabpanel");
        if (!tabpanel || !tabpanel.querySelector(".p-view_contents--primary")) { clear(); return; }
        if (current !== wrapper || panel !== tabpanel) {
            clear();
            current = wrapper;
            panel = tabpanel;
            try {
                const saved = Number(localStorage.getItem(storageKey()));
                channelWidth = saved >= 120 && saved <= 600 ? saved : sidebar.getBoundingClientRect().width || 216;
            } catch (_) { channelWidth = 216; }
            panel.style.setProperty("--nop-channel-width", `${channelWidth}px`);
        }
        if (!style.isConnected) document.head.append(style);
        if (!themed) themeTokens(sidebar);
        panel.setAttribute("data-nop-channels-right", "");
        document.documentElement.setAttribute("data-nop-slack-layout", "channels-right");
    }

    function schedule() { if (!pending) pending = requestAnimationFrame(refresh); }

    function pointerDown(event) {
        const resizer = event.target.closest?.(".p-ia4_client__resizer--sidebar");
        if (!resizer || event.button !== 0 || !panel || !document.documentElement.hasAttribute("data-nop-slack-layout")) return;
        const sidebar = panel.querySelector(".p-view_contents--sidebar");
        drag = { id: event.pointerId, x: event.clientX, width: sidebar.getBoundingClientRect().width, element: resizer };
        resizer.setPointerCapture(event.pointerId);
        event.preventDefault();
        event.stopImmediatePropagation();
    }

    function pointerMove(event) {
        if (!drag || event.pointerId !== drag.id || !panel) return;
        const max = Math.max(100, panel.getBoundingClientRect().width * .45);
        channelWidth = Math.max(Math.min(160, max), Math.min(max, drag.width - (event.clientX - drag.x)));
        panel.style.setProperty("--nop-channel-width", `${channelWidth}px`);
        event.preventDefault();
        event.stopImmediatePropagation();
    }

    function pointerUp(event) {
        if (!drag || event.pointerId !== drag.id) return;
        try { drag.element.releasePointerCapture(drag.id); localStorage.setItem(storageKey(), String(channelWidth)); } catch (_) {}
        drag = null;
        event.preventDefault();
        event.stopImmediatePropagation();
    }

    observer.observe(document.documentElement, { childList: true, subtree: true });
    addEventListener("popstate", schedule);
    addEventListener("hashchange", schedule);
    document.addEventListener("pointerdown", pointerDown, true);
    document.addEventListener("pointermove", pointerMove, true);
    document.addEventListener("pointerup", pointerUp, true);
    document.addEventListener("pointercancel", pointerUp, true);
    window.__nopSlackLayout = {
        refresh: schedule, metadata, setTheme,
        dispose() {
            observer.disconnect();
            removeEventListener("popstate", schedule);
            removeEventListener("hashchange", schedule);
            document.removeEventListener("pointerdown", pointerDown, true);
            document.removeEventListener("pointermove", pointerMove, true);
            document.removeEventListener("pointerup", pointerUp, true);
            document.removeEventListener("pointercancel", pointerUp, true);
            if (pending) cancelAnimationFrame(pending);
            clear();
            style.remove(); themeStyle.remove();
            delete window.__nopSlackLayout;
        }
    };
    setTheme(appearance);
    refresh();
})();

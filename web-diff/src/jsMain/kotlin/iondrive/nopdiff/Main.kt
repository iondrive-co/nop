package iondrive.nopdiff

import kotlinx.browser.window

/**
 * Bundle entry point. Registers the `<nop-diff>` custom element and publishes the imperative API on
 * `window.NopDiff`. Runs once when the script loads.
 */
fun main() {
    val w = window.asDynamic()
    // The element calls back into this Kotlin renderer. Use an anonymous `fun` (a real JS function),
    // not `::renderShadow` (a reference object that JS can't call directly).
    w.__nopDiffRender = fun(root: dynamic, diff: String?, files: dynamic) {
        renderShadow(root, diff, files)
    }

    registerElement()

    val api = js("({})")
    api.renderUnifiedDiff = fun(target: dynamic, diffText: String, theme: String?) {
        renderUnifiedDiff(target, diffText, theme ?: "auto")
    }
    api.renderStructured = fun(target: dynamic, files: dynamic, theme: String?) {
        renderStructured(target, files, theme ?: "auto")
    }
    w.NopDiff = api
}

/**
 * Defines and registers the `<nop-diff>` element. Written in ES5 (the `js()` intrinsic's parser
 * rejects ES6 `class`), using the standard `Reflect.construct` pattern to extend the real
 * HTMLElement, and delegating rendering to the Kotlin function on `window.__nopDiffRender`.
 * Compiled into the bundle — no runtime eval, so it is safe under a `script-src 'self'` CSP.
 *
 * Properties: `.diff` (unified-diff string) and `.files` (structured array); setting either renders.
 * Attribute: `theme` = `light` | `dark` | `auto` (CSS-only — reacts without a re-render).
 */
private fun registerElement() {
    js(
        """
        var reg = (typeof customElements !== 'undefined') ? customElements : null;
        if (reg && !reg.get('nop-diff')) {
          var Ctor = function () { return Reflect.construct(HTMLElement, [], Ctor); };
          Ctor.prototype = Object.create(HTMLElement.prototype);
          Ctor.prototype.constructor = Ctor;
          Object.setPrototypeOf(Ctor, HTMLElement);
          Ctor.prototype.connectedCallback = function () {
            if (!this._root) this._root = this.attachShadow({ mode: 'open' });
            this._render();
          };
          Ctor.prototype._render = function () {
            if (!this._root) return;
            var d = (this._diff == null) ? null : this._diff;
            var f = (this._files == null) ? undefined : this._files;
            window.__nopDiffRender(this._root, d, f);
          };
          Object.defineProperty(Ctor.prototype, 'diff', {
            set: function (v) { this._diff = v; this._files = null; this._render(); },
            get: function () { return this._diff; }
          });
          Object.defineProperty(Ctor.prototype, 'files', {
            set: function (v) { this._files = v; this._diff = null; this._render(); },
            get: function () { return this._files; }
          });
          reg.define('nop-diff', Ctor);
        }
        """
    )
}

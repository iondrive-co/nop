package iondrive.nopdiff

import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * Imperative entry points, exposed to JS on `window.NopDiff` by [main]. Both create an encapsulated
 * `<nop-diff>` element (Shadow DOM) inside [target], so callers never have to deal with the
 * stylesheet or DOM structure.
 */

/** Render a unified-diff string (`git diff` output) into [target]. */
fun renderUnifiedDiff(target: HTMLElement, diffText: String, theme: String = "auto") {
    val el = makeElement(theme)
    mount(target, el)
    el.asDynamic().diff = diffText
}

/** Render an already-parsed structured diff (e.g. chad's `FileDiff[]`) into [target]. */
fun renderStructured(target: HTMLElement, files: dynamic, theme: String = "auto") {
    val el = makeElement(theme)
    mount(target, el)
    el.asDynamic().files = files
}

private fun makeElement(theme: String): HTMLElement {
    val el = document.createElement("nop-diff") as HTMLElement
    el.setAttribute("theme", theme)
    return el
}

private fun mount(target: HTMLElement, el: HTMLElement) {
    while (target.firstChild != null) target.removeChild(target.firstChild!!)
    target.appendChild(el)
}

/**
 * (Re)render into a `<nop-diff>`'s shadow root. Called from the element's lifecycle (see [main]'s
 * registration). Rebuilds the stylesheet + content each time — cheap, and keeps state trivial.
 */
fun renderShadow(root: dynamic, diff: String?, files: dynamic) {
    while (root.firstChild != null) root.removeChild(root.firstChild)

    val style = document.createElement("style")
    style.textContent = CSS
    root.appendChild(style)

    val container = document.createElement("div") as HTMLElement
    container.className = "nd-container"
    root.appendChild(container)

    val files_present = files != null && files != undefined
    val parsed: List<DiffFile> = when {
        files_present -> StructuredInput.fromDynamic(files)
        diff != null -> UnifiedDiffParser.parse(diff)
        else -> emptyList()
    }
    DomRenderer.render(container, parsed)
}

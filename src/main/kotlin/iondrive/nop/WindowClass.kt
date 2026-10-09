package iondrive.nop

import java.awt.Toolkit

/**
 * Gives nop's windows the X11 window class named by `NOP_WM_CLASS`, instead of the one AWT derives
 * from the main class (`iondrive-nop-MainKt`). Docks and task bars group windows by that class, so
 * without it a second nop — one run as another user and shown on this desktop through xpra, say —
 * is filed under the user's own nop, and its own launcher never sees a window to raise or minimise.
 *
 * AWT keeps the class in a private field of its X11 toolkit and reads it as each window is created,
 * so this runs before the first window, and needs `--add-opens java.desktop/sun.awt.X11` (see
 * build.gradle.kts). Anywhere that is not X11 it does nothing.
 */
object WindowClass {
    fun apply(name: String? = System.getenv("NOP_WM_CLASS")) {
        val wanted = name?.trim()?.takeIf { it.isNotEmpty() } ?: return
        runCatching {
            val toolkit = Toolkit.getDefaultToolkit()
            if (toolkit.javaClass.name != "sun.awt.X11.XToolkit") return
            toolkit.javaClass.getDeclaredField("awtAppClassName").apply { isAccessible = true }.set(null, wanted)
            Log.info("window class $wanted")
        }.onFailure { Log.warn("could not set the window class to $wanted: $it") }
    }
}

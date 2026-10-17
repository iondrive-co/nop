package iondrive.nop.agent

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.lang.ref.Reference

/**
 * Mounts a share the way a file manager does — through GIO, so GVFS makes the mount — with nop
 * answering the share's request for a password itself. That answer is what `gio mount` cannot give:
 * told to remember the password, GVFS keeps it in the keyring, where the next mount of the share,
 * nop's or the file manager's, finds it without asking anyone.
 *
 * GIO is asynchronous and reports through GLib's main loop, which nothing in nop runs. So [mount]
 * lends GLib the calling thread for the length of one mount: a main context made for the call is
 * that thread's default while it lasts, and every callback the mount makes — the request for a
 * password, the result — runs on it, inside [mount], and never once [mount] has returned.
 */
internal object GioMount {

    /** What a share asked for. [user] and [domain] are what GVFS offers when not told otherwise. */
    data class Ask(
        val user: String,
        val domain: String,
        val needsUser: Boolean,
        val needsDomain: Boolean,
    )

    sealed interface Outcome {
        data object Mounted : Outcome

        /** The share wants a password nop was not given, or [refused] the one it was. */
        data class NeedsPassword(val ask: Ask, val refused: Boolean) : Outcome

        data class Failed(val message: String) : Outcome
    }

    // GAskPasswordFlags, GPasswordSave, GMountOperationResult and the one GIOErrorEnum value read here.
    private const val NEED_USERNAME = 1 shl 1
    private const val NEED_DOMAIN = 1 shl 2
    private const val SAVE_NEVER = 0
    private const val SAVE_PERMANENTLY = 2
    private const val HANDLED = 0
    private const val ABORTED = 1
    private const val ALREADY_MOUNTED = 17

    private const val POLL_MS = 20L

    /** How long a cancelled mount is given to say so before nop stops waiting for it. */
    private const val CANCEL_GRACE_MS = 5_000L

    private interface GLib : Library {
        fun g_main_context_new(): Pointer
        fun g_main_context_push_thread_default(context: Pointer)
        fun g_main_context_pop_thread_default(context: Pointer)
        fun g_main_context_iteration(context: Pointer, mayBlock: Int): Int
        fun g_main_context_unref(context: Pointer)
        fun g_error_free(error: Pointer)
    }

    private interface GObject : Library {
        fun g_object_unref(instance: Pointer)
        fun g_signal_connect_data(instance: Pointer, signal: String, handler: Callback, data: Pointer?, destroy: Pointer?, flags: Int): NativeLong
        fun g_signal_stop_emission_by_name(instance: Pointer, signal: String)
    }

    private interface Gio : Library {
        fun g_file_new_for_uri(uri: String): Pointer
        fun g_file_mount_enclosing_volume(file: Pointer, flags: Int, operation: Pointer, cancellable: Pointer, callback: Ready, data: Pointer?)
        fun g_file_mount_enclosing_volume_finish(file: Pointer, result: Pointer, error: PointerByReference): Int
        fun g_mount_operation_new(): Pointer
        fun g_mount_operation_set_username(operation: Pointer, username: String?)
        fun g_mount_operation_set_domain(operation: Pointer, domain: String?)
        fun g_mount_operation_set_password(operation: Pointer, password: String?)
        fun g_mount_operation_set_password_save(operation: Pointer, save: Int)
        fun g_mount_operation_reply(operation: Pointer, result: Int)
        fun g_cancellable_new(): Pointer
        fun g_cancellable_cancel(cancellable: Pointer)
        fun g_io_error_quark(): Int
    }

    /** GMountOperation's `ask-password` signal. */
    interface AskPassword : Callback {
        fun invoke(operation: Pointer, message: String?, user: String?, domain: String?, flags: Int, data: Pointer?)
    }

    /** A GAsyncReadyCallback. */
    interface Ready : Callback {
        fun invoke(source: Pointer?, result: Pointer, data: Pointer?)
    }

    private class Libraries(val glib: GLib, val gobject: GObject, val gio: Gio)

    /** By soname: the unversioned names belong to the -dev packages, which a desktop need not have. */
    private val libraries: Libraries? by lazy {
        runCatching {
            Libraries(
                Native.load("libglib-2.0.so.0", GLib::class.java),
                Native.load("libgobject-2.0.so.0", GObject::class.java),
                Native.load("libgio-2.0.so.0", Gio::class.java),
            )
        }.getOrNull()
    }

    /**
     * Mounts the share at [url], answering its request for a password with [credentials] — once:
     * a second request means the first answer was turned down. Without [credentials] the mount
     * still succeeds when GVFS needs no password or finds one in the keyring. Blocks until the
     * mount is made or refused, or [timeoutMs] has passed.
     */
    fun mount(url: String, credentials: NetworkShare.Credentials?, timeoutMs: Long): Outcome {
        val libs = libraries ?: return Outcome.Failed("GIO, which mounts shares, could not be loaded")
        val glib = libs.glib
        val gobject = libs.gobject
        val gio = libs.gio
        val context = glib.g_main_context_new()
        glib.g_main_context_push_thread_default(context)
        val file = gio.g_file_new_for_uri(url)
        val operation = gio.g_mount_operation_new()
        val cancellable = gio.g_cancellable_new()
        var asked = 0
        var lastAsk: Ask? = null
        var outcome: Outcome? = null
        val onAsk = object : AskPassword {
            override fun invoke(operation: Pointer, message: String?, user: String?, domain: String?, flags: Int, data: Pointer?) {
                // Answered here and only here. GMountOperation's own handler would queue an
                // "unhandled" reply on GLib's global main context, which nothing in nop runs.
                gobject.g_signal_stop_emission_by_name(operation, "ask-password")
                val ask = ask(flags, user, domain).also { lastAsk = it }
                asked++
                if (credentials == null || asked > 1) {
                    gio.g_mount_operation_reply(operation, ABORTED)
                    return
                }
                if (ask.needsUser) gio.g_mount_operation_set_username(operation, credentials.user)
                if (ask.needsDomain) gio.g_mount_operation_set_domain(operation, credentials.domain.ifBlank { ask.domain })
                gio.g_mount_operation_set_password(operation, credentials.password)
                gio.g_mount_operation_set_password_save(operation, if (credentials.remember) SAVE_PERMANENTLY else SAVE_NEVER)
                gio.g_mount_operation_reply(operation, HANDLED)
            }
        }
        val onReady = object : Ready {
            override fun invoke(source: Pointer?, result: Pointer, data: Pointer?) {
                val error = PointerByReference()
                val mounted = gio.g_file_mount_enclosing_volume_finish(file, result, error) != 0
                // A GError is { GQuark domain; gint code; gchar *message; }.
                val failure = error.value
                val alreadyMounted = failure != null &&
                    failure.getInt(0) == gio.g_io_error_quark() && failure.getInt(4) == ALREADY_MOUNTED
                val message = failure?.getPointer(8)?.getString(0)
                if (failure != null) glib.g_error_free(failure)
                outcome = outcome(mounted || alreadyMounted, message, lastAsk, asked, credentials != null)
            }
        }
        try {
            gobject.g_signal_connect_data(operation, "ask-password", onAsk, null, null, 0)
            gio.g_file_mount_enclosing_volume(file, 0, operation, cancellable, onReady, null)
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            var cancelledAt: Long? = null
            while (outcome == null) {
                if (glib.g_main_context_iteration(context, 0) == 0) Thread.sleep(POLL_MS)
                val now = System.nanoTime()
                if (cancelledAt == null && now > deadline) {
                    gio.g_cancellable_cancel(cancellable)
                    cancelledAt = now
                } else if (cancelledAt != null && now - cancelledAt > CANCEL_GRACE_MS * 1_000_000) {
                    break
                }
            }
            // A mount nop cancelled fails with GIO's "Operation was cancelled", which says nothing.
            val finished = outcome
            return if (finished == null || (cancelledAt != null && finished !is Outcome.Mounted)) {
                Outcome.Failed("no answer from the share in ${timeoutMs / 1000} s")
            } else {
                finished
            }
        } finally {
            // Held to here: GLib calls these through pointers JNA frees once they are collected.
            Reference.reachabilityFence(onAsk)
            Reference.reachabilityFence(onReady)
            gobject.g_object_unref(cancellable)
            gobject.g_object_unref(operation)
            gobject.g_object_unref(file)
            glib.g_main_context_pop_thread_default(context)
            glib.g_main_context_unref(context)
        }
    }

    internal fun ask(flags: Int, user: String?, domain: String?): Ask = Ask(
        user = user.orEmpty(),
        domain = domain.orEmpty(),
        needsUser = (flags and NEED_USERNAME) != 0,
        needsDomain = (flags and NEED_DOMAIN) != 0,
    )

    /**
     * What a finished mount came to. A request for a password that went unanswered — there were no
     * credentials, or they had been given once already — is why it failed, whatever GIO's message
     * says ("Password dialog cancelled", which is nop's own abort).
     */
    internal fun outcome(mounted: Boolean, message: String?, ask: Ask?, asked: Int, hadCredentials: Boolean): Outcome = when {
        mounted -> Outcome.Mounted
        ask != null && (!hadCredentials || asked > 1) -> Outcome.NeedsPassword(ask, refused = hadCredentials)
        else -> Outcome.Failed(message?.takeIf { it.isNotBlank() } ?: "the share gave no reason")
    }
}

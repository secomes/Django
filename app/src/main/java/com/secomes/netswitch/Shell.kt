package com.secomes.netswitch

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/** Client side of [ShellService], bound through Shizuku. */
object Shell {
    data class Result(val code: Int, val out: String) {
        val ok get() = code == 0
    }

    @Volatile
    private var service: IShellService? = null

    // Only touched on the main thread.
    private var pending: CompletableDeferred<IShellService>? = null

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) return
            val s = IShellService.Stub.asInterface(binder)
            service = s
            pending?.complete(s)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    fun isRunning(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.isPreV11()
    } catch (e: Exception) {
        false
    }

    fun isAvailable(): Boolean = isRunning() &&
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    private suspend fun get(): IShellService? {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        if (!isAvailable()) return null
        val deferred = withContext(Dispatchers.Main) {
            pending?.takeIf { !it.isCompleted }
                ?: CompletableDeferred<IShellService>().also {
                    pending = it
                    Shizuku.bindUserService(args, connection)
                }
        }
        return withTimeoutOrNull(10_000) { deferred.await() }
    }

    suspend fun run(vararg argv: String): Result = withContext(Dispatchers.IO) {
        val s = get() ?: return@withContext Result(-1, "Shizuku를 사용할 수 없음")
        try {
            val raw = s.exec(arrayOf(*argv))
            val nl = raw.indexOf('\n')
            if (nl < 0) Result(raw.toIntOrNull() ?: -1, "")
            else Result(raw.substring(0, nl).toIntOrNull() ?: -1, raw.substring(nl + 1))
        } catch (e: Exception) {
            service = null
            Result(-1, e.toString())
        }
    }
}

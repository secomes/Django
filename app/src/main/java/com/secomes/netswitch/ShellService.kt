package com.secomes.netswitch

import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Runs inside the process Shizuku starts with shell (ADB) privileges,
 * so commands like `svc wifi` and `cmd wifi` are allowed here.
 */
class ShellService : IShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(argv: Array<String>): String {
        return try {
            val p = ProcessBuilder(*argv).redirectErrorStream(true).start()
            var out = ""
            val reader = thread { out = p.inputStream.bufferedReader().readText() }
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                reader.join(1000)
                return "124\n$out"
            }
            reader.join(2000)
            "${p.exitValue()}\n$out"
        } catch (e: Exception) {
            "-1\n$e"
        }
    }
}

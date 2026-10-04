package com.sqlai.agente.core.linux

import android.content.Context
import android.os.Build
import com.sqlai.agente.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Embedded Linux subsystem.
 *
 * Strategy (Termux/PRoot adapted):
 *  1. Extract a minimal rootfs bootstrap (busybox + bash + python) from APK assets
 *     into app-private storage — no root required, no system partition touched.
 *  2. Spawn `/system/bin/sh` or the extracted bash through a PTY (see proc_exec.cpp)
 *     so interactive programs (git, python, package managers) behave normally.
 *  3. When a full PRoot rootfs is present (`rootfs/`), the launcher re-execs through
 *     proot for chroot-like isolation (fakeroot, bind mounts) without privileges.
 *
 * All execution happens in the app's own UID — nothing escapes the sandbox.
 */
class LinuxSubsystem(private val context: Context) {

    data class SessionState(
        val running: Boolean = false,
        val cwd: String = "",
        val banner: String = "",
    )

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var ptyHandle: Long = 0L

    val rootDir: File get() = File(context.filesDir, "linux")
    val binDir: File get() = File(rootDir, "bin")
    val workDir: File get() = File(rootDir, "work")

    /** Extracts bootstrap scripts + busybox applets shipped in assets/linux/. */
    suspend fun bootstrap(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            listOf(rootDir, binDir, workDir).forEach { it.mkdirs() }
            val assets = context.assets.list("linux") ?: emptyArray()
            for (name in assets) {
                val out = File(binDir, name)
                if (out.exists() && out.length() > 0) continue
                context.assets.open("linux/$name").use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                out.setExecutable(true, false)
            }
            _state.value = _state.value.copy(banner = banner())
        }
    }

    /** Starts an interactive shell session inside the sandbox. */
    suspend fun startShell(shellPath: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (ptyHandle != 0L) closeSession()
            bootstrap().getOrThrow()

            val shell = shellPath
                ?: pickShell()
            val env = buildEnv()
            val args = arrayOf("-l")
            ptyHandle = nativeOpenPty(shell, args, workDir.absolutePath, env)
            if (ptyHandle == 0L) throw IllegalStateException("PTY spawn failed")

            _state.value = SessionState(
                running = true,
                cwd = workDir.absolutePath,
                banner = banner(),
            )
        }
    }

    /** Writes a command line to the shell (newline is appended by the TTY layer). */
    suspend fun write(data: String): Boolean = withContext(Dispatchers.IO) {
        ptyHandle != 0L && nativeWritePty(ptyHandle, data) >= 0
    }

    suspend fun readOutput(): String = withContext(Dispatchers.IO) {
        if (ptyHandle == 0L) return@withContext ""
        val chunk = nativeReadPty(ptyHandle)
        if (!nativeAlive(ptyHandle)) {
            _state.value = _state.value.copy(running = false)
        }
        chunk
    }

    suspend fun resize(cols: Int, rows: Int) = withContext(Dispatchers.IO) {
        if (ptyHandle != 0L) nativeResizePty(ptyHandle, cols, rows)
    }

    fun closeSession() {
        if (ptyHandle != 0L) {
            nativeClosePty(ptyHandle)
            ptyHandle = 0L
        }
        _state.value = _state.value.copy(running = false)
    }

    fun onHostTrim(level: Int) {
        // Long-running shells are cheap; only drop cached banner state on pressure.
        if (level >= 80) closeSession()
    }

    /** Runs a one-shot command (used by strategy scripts + package installs). */
    suspend fun execOnce(command: String, timeoutMs: Long = 60_000): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                File(rootDir, "tmp").mkdirs()
                val pb = ProcessBuilder("/system/bin/sh", "-c", command)
                    .directory(workDir)
                    .redirectErrorStream(true)
                val env = pb.environment()
                env["HOME"] = rootDir.absolutePath
                env["PATH"] = "${binDir.absolutePath}:/system/bin:/system/xbin"
                env["TMPDIR"] = File(rootDir, "tmp").absolutePath
                env["SQLAI_SANDBOX"] = "1"

                val proc = pb.start()
                val out = proc.inputStream.bufferedReader().readText()
                val finished = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    throw IllegalStateException("timeout")
                }
                if (proc.exitValue() != 0 && out.isBlank()) {
                    throw IllegalStateException("exit ${proc.exitValue()}")
                }
                out
            }
        }

    private fun pickShell(): String {
        val candidates = listOf(
            File(binDir, "bash"),
            File(binDir, "sh"),
            File(rootDir, "rootfs/bin/bash"),
            File("/system/bin/sh"),
        )
        return candidates.firstOrNull { it.canExecute() }?.absolutePath
            ?: "/system/bin/sh"
    }

    private fun buildEnv(): Array<String> {
        val tmp = File(rootDir, "tmp").apply { mkdirs() }
        return arrayOf(
            "HOME=${rootDir.absolutePath}",
            "PATH=${binDir.absolutePath}:/system/bin:/system/xbin",
            "TMPDIR=${tmp.absolutePath}",
            "PREFIX=${rootDir.absolutePath}",
            "LANG=en_US.UTF-8",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "SQLAI_SANDBOX=1",
        )
    }

    private fun banner(): String = buildString {
        appendLine("SQL AI AGENTE · embedded linux sandbox")
        appendLine("uid=${Build.VERSION.SDK_INT} root=${rootDir.absolutePath}")
        appendLine("shell=${pickShell()}")
        append("busybox=${if (File(binDir, "busybox").canExecute()) "yes" else "system-only"}")
    }

    private external fun nativeOpenPty(cmd: String, args: Array<String>, cwd: String, envp: Array<String>): Long
    private external fun nativeReadPty(handle: Long): String
    private external fun nativeWritePty(handle: Long, data: String): Int
    private external fun nativeResizePty(handle: Long, cols: Int, rows: Int)
    private external fun nativeAlive(handle: Long): Boolean
    private external fun nativeClosePty(handle: Long)

    init {
        runCatching { System.loadLibrary("sqlai_proc") }
    }
}

// proc_exec.cpp — PTY-backed child process launcher for the embedded Linux subsystem.
// Mirrors Termux's approach: fork + openpty + execve so bash/python get a real tty
// (needed for colors, line editing, and interactive prompts).
#include <jni.h>
#include <android/log.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cerrno>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <sys/types.h>

#define TAG "SqlAiProc"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#include <pty.h>
#include <vector>
#include <string>

namespace {

struct PtySession {
    int master = -1;
    pid_t pid = -1;
    bool alive = false;
};

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_sqlai_agente_core_linux_LinuxSubsystem_nativeOpenPty(
        JNIEnv* env, jobject, jstring jcmd, jobjectArray jargs, jstring jcwd, jobjectArray jenvp) {
    int master = -1, slave = -1;
    struct termios termios{};
    if (openpty(&master, &slave, nullptr, nullptr, nullptr) < 0) {
        LOGE("openpty failed: %s", strerror(errno));
        return 0;
    }
    tcgetattr(slave, &termios);
    cfmakeraw(&termios);
    termios.c_oflag |= OPOST;
    tcsetattr(slave, TCSANOW, &termios);

    const char* cmd = env->GetStringUTFChars(jcmd, nullptr);

    std::vector<char*> argv;
    const int argc = env->GetArrayLength(jargs);
    argv.push_back(const_cast<char*>(cmd));
    for (int i = 0; i < argc; ++i) {
        auto js = (jstring)env->GetObjectArrayElement(jargs, i);
        argv.push_back(const_cast<char*>(env->GetStringUTFChars(js, nullptr)));
    }
    argv.push_back(nullptr);

    const char* cwd = env->GetStringUTFChars(jcwd, nullptr);

    std::vector<char*> envp;
    const int envc = env->GetArrayLength(jenvp);
    for (int i = 0; i < envc; ++i) {
        auto js = (jstring)env->GetObjectArrayElement(jenvp, i);
        envp.push_back(const_cast<char*>(env->GetStringUTFChars(js, nullptr)));
    }
    envp.push_back(nullptr);

    pid_t pid = fork();
    if (pid == 0) {
        setsid();
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, 0); dup2(slave, 1); dup2(slave, 2);
        if (slave > 2) close(slave);
        close(master);
        if (cwd && chdir(cwd) != 0) { /* non-fatal */ }
        execve(cmd, argv.data(), envp.data());
        _exit(127);
    } else if (pid < 0) {
        LOGE("fork failed: %s", strerror(errno));
        close(master); close(slave);
        return 0;
    }

    close(slave);
    fcntl(master, F_SETFL, O_NONBLOCK);

    auto* s = new PtySession{master, pid, true};
    // Release JNI UTF chars (argv/env strings are already copied by execve).
    env->ReleaseStringUTFChars(jcmd, argv[0]);
    for (int i = 0; i < argc; ++i) {
        auto js = (jstring)env->GetObjectArrayElement(jargs, i);
        env->ReleaseStringUTFChars(js, argv[i + 1]);
    }
    for (int i = 0; i < envc; ++i) {
        auto js = (jstring)env->GetObjectArrayElement(jenvp, i);
        env->ReleaseStringUTFChars(js, envp[i]);
    }
    env->ReleaseStringUTFChars(jcwd, cwd);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT jstring JNICALL
Java_com_sqlai_agente_core_linux_LinuxSubsystem_nativeReadPty(JNIEnv* env, jobject, jlong handle) {
    auto* s = reinterpret_cast<PtySession*>(handle);
    if (!s || s->master < 0) return env->NewStringUTF("");
    char buf[4096];
    std::string acc;
    ssize_t n;
    while ((n = read(s->master, buf, sizeof(buf))) > 0) acc.append(buf, (size_t)n);
    if (n < 0 && errno != EAGAIN && errno != EIO) s->alive = false;
    if (n == 0) s->alive = false;
    return env->NewStringUTF(acc.c_str());
}

JNIEXPORT jint JNICALL
Java_com_sqlai_agente_core_linux_LinuxSubsystem_nativeWritePty(JNIEnv* env, jobject, jlong handle, jstring jdata) {
    auto* s = reinterpret_cast<PtySession*>(handle);
    if (!s || s->master < 0) return -1;
    const char* data = env->GetStringUTFChars(jdata, nullptr);
    ssize_t n = write(s->master, data, strlen(data));
    env->ReleaseStringUTFChars(jdata, data);
    return (jint)n;
}

JNIEXPORT void JNICALL
Java_com_sqlai_agente_core_linux_LinuxSubsystem_nativeResizePty(JNIEnv*, jobject, jlong handle, jint cols, jint rows) {
    auto* s = reinterpret_cast<PtySession*>(handle);
    if (!s || s->master < 0) return;
    struct winsize ws{};
    ws.ws_col = (unsigned short)cols;
    ws.ws_row = (unsigned short)rows;
    ioctl(s->master, TIOCSWINSZ, &ws);
}

JNIEXPORT jboolean JNICALL
Java_com_sqlai_agente_core_linux_LinuxSubsystem_nativeAlive(JNIEnv*, jobject, jlong handle) {
    auto* s = reinterpret_cast<PtySession*>(handle);
    if (!s || !s->alive) return JNI_FALSE;
    int status = 0;
    pid_t r = waitpid(s->pid, &status, WNOHANG);
    if (r == s->pid) { s->alive = false; return JNI_FALSE; }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_sqlai_agente_core_linux_LinuxSubsystem_nativeClosePty(JNIEnv*, jobject, jlong handle) {
    auto* s = reinterpret_cast<PtySession*>(handle);
    if (!s) return;
    if (s->pid > 0) { kill(-s->pid, SIGHUP); kill(s->pid, SIGHUP); waitpid(s->pid, nullptr, 0); }
    if (s->master >= 0) close(s->master);
    delete s;
}

} // extern "C"

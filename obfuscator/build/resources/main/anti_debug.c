#include <jni.h>
#include <windows.h>
#include <stdint.h>
#include <string.h>

/* Embedded JAR hash — filled in by ClangCompiler before compilation */
static const uint8_t EXPECTED_HASH[32] = { 0 };

/* Simple SHA-256 implementation (single-file, no deps) */
#include "sha256.h"

static volatile int _triggered = 0;

static void _corrupt_state(void) {
    /* Silently flip internal state so outputs are wrong but no crash/error */
    _triggered = 1;
}

/* Anti-debug: IsDebuggerPresent + timing check */
static void _check_debugger(void) {
    if (IsDebuggerPresent()) { _corrupt_state(); return; }

    /* Timing: RDTSC-based — a debugger slows execution by orders of magnitude */
    LARGE_INTEGER t1, t2, freq;
    QueryPerformanceFrequency(&freq);
    QueryPerformanceCounter(&t1);
    volatile int x = 0;
    for (int i = 0; i < 10000; i++) x += i;
    QueryPerformanceCounter(&t2);
    double elapsed_ms = (double)(t2.QuadPart - t1.QuadPart) / freq.QuadPart * 1000.0;
    if (elapsed_ms > 50.0) { _corrupt_state(); } /* >50ms for trivial loop = debugger */
}

/* Integrity: hash the running JAR and compare to embedded expected hash */
static void _check_jar_integrity(JNIEnv *env) {
    jclass system = (*env)->FindClass(env, "java/lang/System");
    jmethodID getProp = (*env)->GetStaticMethodID(env, system,
        "getProperty", "(Ljava/lang/String;)Ljava/lang/String;");
    jstring key = (*env)->NewStringUTF(env, "java.class.path");
    jstring val = (jstring)(*env)->CallStaticObjectMethod(env, system, getProp, key);
    if (!val) return;

    const char *path = (*env)->GetStringUTFChars(env, val, NULL);
    /* Read file and SHA-256 hash it */
    HANDLE f = CreateFileA(path, GENERIC_READ, FILE_SHARE_READ, NULL,
                           OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    (*env)->ReleaseStringUTFChars(env, val, path);
    if (f == INVALID_HANDLE_VALUE) return;

    SHA256_CTX ctx; sha256_init(&ctx);
    uint8_t buf[4096]; DWORD read;
    while (ReadFile(f, buf, sizeof(buf), &read, NULL) && read > 0)
        sha256_update(&ctx, buf, read);
    CloseHandle(f);

    uint8_t actual[32]; sha256_final(&ctx, actual);
    if (memcmp(actual, EXPECTED_HASH, 32) != 0) _corrupt_state();
}

/* JVM agent detection */
JNIEXPORT void JNICALL Java_com_obf_runtime_AntiAnalysis_checkAgents(JNIEnv *env, jclass cls) {
    _check_debugger();
    _check_jar_integrity(env);
}

/* DLL entry point — run checks at load time */
BOOL WINAPI DllMain(HINSTANCE hInst, DWORD reason, LPVOID reserved) {
    if (reason == DLL_PROCESS_ATTACH) {
        _check_debugger();
    }
    return TRUE;
}

JNIEXPORT jboolean JNICALL Java_com_obf_runtime_AntiAnalysis_isCorrupted(JNIEnv *env, jclass cls) {
    return (jboolean)_triggered;
}

// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.voice;

import kotlin.jvm.functions.Function1;

/**
 * The object sherpa-onnx's native TTS calls back with each chunk of samples. Its JNI (v1.13.8
 * jni/offline-tts.cc, CallCallback) looks the method up with
 * {@code GetMethodID(cls, "invoke", "([F)Ljava/lang/Integer;")} on the callback's own class. A Kotlin lambda
 * or function reference does not have that method: Kotlin 2.x compiles it through invokedynamic and D8 turns
 * that into a class with only the erased {@code invoke(Ljava/lang/Object;)Ljava/lang/Object;}. The lookup
 * then fails, sherpa leaves a NoSuchMethodError pending and no audio is ever written (Robot 0.1.2).
 *
 * <p>This class is Java so that it really declares {@code public Integer invoke(float[])} (javac adds the
 * erased bridge as well). tools/verify_apk.sh checks the built APK for it and TtsCallbackTest checks it by
 * reflection. The Kotlin side implements {@link TtsSink}.
 *
 * <p>Nothing may escape into native code: an exception left pending there makes sherpa carry on with JNI
 * calls it may not make (CheckJNI aborts the process). A sink that throws stops the generation instead, and
 * the throwable is kept for the caller to log once sherpa returns.
 */
public final class TtsCallback implements Function1<float[], Integer> {
    private static final Integer CONTINUE = 1;
    private static final Integer STOP = 0;

    private final TtsSink sink;
    private volatile Throwable failure;

    public TtsCallback(TtsSink sink) {
        if (sink == null) throw new NullPointerException("sink");
        this.sink = sink;
    }

    /** Called by sherpa-onnx (JNI) on the generating thread; 1 = keep going, 0 = stop. */
    @Override
    public Integer invoke(float[] samples) {
        if (samples == null || failure != null) return STOP;
        try {
            return sink.onSamples(samples) ? CONTINUE : STOP;
        } catch (Throwable t) {
            if (t instanceof InterruptedException) Thread.currentThread().interrupt();
            failure = t;
            return STOP;
        }
    }

    /** What the sink threw, if it did (the generation stopped there). */
    public Throwable failure() {
        return failure;
    }
}

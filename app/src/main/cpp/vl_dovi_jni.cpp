// JNI for Vision's Dolby Vision sample rewriter (native/vldovi, Rust over the MIT dolby_vision crate).
#include <jni.h>
#include <stdint.h>
#include <stddef.h>

extern "C" {
intptr_t vl_dovi_sample(const uint8_t* in, size_t len, int32_t annexb, int32_t len_size, uint32_t flags, uint8_t mode,
                        uint8_t* out, size_t cap, int64_t* counts);
int32_t vl_dovi_info(const uint8_t* in, size_t len, int32_t* info);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_lekathedon_visionlite_player_dv_Dovi_nSample(JNIEnv* env, jclass, jbyteArray in, jint len, jint annexb,
                                                     jint lenSize, jint flags, jint mode, jbyteArray out,
                                                     jlongArray counts) {
    jsize inCap = env->GetArrayLength(in), outCap = env->GetArrayLength(out);
    if (len < 0 || len > inCap) return -1;
    jlong c[2] = {0, 0};
    auto* i = static_cast<uint8_t*>(env->GetPrimitiveArrayCritical(in, nullptr));
    auto* o = static_cast<uint8_t*>(env->GetPrimitiveArrayCritical(out, nullptr));
    intptr_t r = -1;
    if (i && o) r = vl_dovi_sample(i, (size_t) len, annexb, lenSize, (uint32_t) flags, (uint8_t) mode, o, (size_t) outCap,
                                   reinterpret_cast<int64_t*>(c));
    if (o) env->ReleasePrimitiveArrayCritical(out, o, 0);
    if (i) env->ReleasePrimitiveArrayCritical(in, i, JNI_ABORT);
    env->SetLongArrayRegion(counts, 0, 2, c);
    return (jint) r;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_lekathedon_visionlite_player_dv_Dovi_nInfo(JNIEnv* env, jclass, jbyteArray in, jint off, jint len, jintArray info) {
    jsize cap = env->GetArrayLength(in);
    if (off < 0 || len <= 0 || off + len > cap) return -1;
    jint v[2] = {0, 0};
    auto* i = static_cast<uint8_t*>(env->GetPrimitiveArrayCritical(in, nullptr));
    int32_t r = i ? vl_dovi_info(i + off, (size_t) len, reinterpret_cast<int32_t*>(v)) : -1;
    if (i) env->ReleasePrimitiveArrayCritical(in, i, JNI_ABORT);
    env->SetIntArrayRegion(info, 0, 2, v);
    return r;
}

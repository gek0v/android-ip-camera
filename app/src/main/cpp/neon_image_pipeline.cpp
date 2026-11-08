#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <algorithm>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#endif

#define LOG_TAG "NativeImagePipeline"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static inline jint getBufferPosition(JNIEnv* env, jobject buf) {
    if (!buf) return 0;
    static jmethodID posMethod = nullptr;
    if (!posMethod) {
        jclass bufClass = env->FindClass("java/nio/Buffer");
        if (bufClass) {
            posMethod = env->GetMethodID(bufClass, "position", "()I");
        }
    }
    if (posMethod) {
        return env->CallIntMethod(buf, posMethod);
    }
    return 0;
}

static inline bool convertPlanesInternal(
    const uint8_t* y_src,
    const uint8_t* u_src,
    const uint8_t* v_src,
    uint8_t* dst,
    int32_t width,
    int32_t height,
    int32_t y_row_stride,
    int32_t uv_row_stride,
    int32_t uv_pixel_stride
) {
    if (!y_src || !u_src || !v_src || !dst) return false;
    if (width <= 0 || height <= 0) return false;
    if ((width & 1) != 0 || (height & 1) != 0) return false;
    if (y_row_stride < width) return false;
    if (uv_pixel_stride <= 0) return false;
    if (uv_row_stride < (width / 2) * uv_pixel_stride) return false;

    // 1. Copy Y plane
    if (y_row_stride == width) {
        std::memcpy(dst, y_src, static_cast<size_t>(width) * height);
    } else {
        for (int32_t r = 0; r < height; ++r) {
            std::memcpy(dst + r * width, y_src + r * y_row_stride, width);
        }
    }

    // 2. Interleave UV planes into NV21 format {V, U, V, U...}
    uint8_t* dst_uv = dst + (width * height);
    const int32_t chroma_width = width / 2;
    const int32_t chroma_height = height / 2;

    // Fast Path 1: Already NV21 (uv_pixel_stride == 2 and v_src + 1 == u_src)
    if (uv_pixel_stride == 2 && (v_src + 1 == u_src)) {
        if (uv_row_stride == width) {
            std::memcpy(dst_uv, v_src, static_cast<size_t>(width) * chroma_height);
        } else {
            for (int32_t r = 0; r < chroma_height; ++r) {
                std::memcpy(dst_uv + r * width, v_src + r * uv_row_stride, width);
            }
        }
        return true;
    }

    // Fast Path 2: NV12 (uv_pixel_stride == 2 and u_src + 1 == v_src)
    if (uv_pixel_stride == 2 && (u_src + 1 == v_src)) {
        for (int32_t r = 0; r < chroma_height; ++r) {
            const uint8_t* src_row = u_src + r * uv_row_stride;
            uint8_t* dst_row = dst_uv + r * width;
            int32_t c = 0;
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
            for (; c <= chroma_width - 16; c += 16) {
                uint8x16x2_t uv = vld2q_u8(src_row + c * 2);
                uint8x16x2_t vu;
                vu.val[0] = uv.val[1];
                vu.val[1] = uv.val[0];
                vst2q_u8(dst_row + c * 2, vu);
            }
            for (; c <= chroma_width - 8; c += 8) {
                uint8x8x2_t uv = vld2_u8(src_row + c * 2);
                uint8x8x2_t vu;
                vu.val[0] = uv.val[1];
                vu.val[1] = uv.val[0];
                vst2_u8(dst_row + c * 2, vu);
            }
#endif
            for (; c < chroma_width; ++c) {
                dst_row[c * 2]     = src_row[c * 2 + 1];
                dst_row[c * 2 + 1] = src_row[c * 2];
            }
        }
        return true;
    }

    // Fast Path 3: Planar I420 (uv_pixel_stride == 1)
    if (uv_pixel_stride == 1) {
        for (int32_t r = 0; r < chroma_height; ++r) {
            const uint8_t* u_row = u_src + r * uv_row_stride;
            const uint8_t* v_row = v_src + r * uv_row_stride;
            uint8_t* dst_row = dst_uv + r * width;
            int32_t c = 0;
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
            for (; c <= chroma_width - 16; c += 16) {
                uint8x16_t u = vld1q_u8(u_row + c);
                uint8x16_t v = vld1q_u8(v_row + c);
                uint8x16x2_t vu;
                vu.val[0] = v;
                vu.val[1] = u;
                vst2q_u8(dst_row + c * 2, vu);
            }
            for (; c <= chroma_width - 8; c += 8) {
                uint8x8_t u = vld1_u8(u_row + c);
                uint8x8_t v = vld1_u8(v_row + c);
                uint8x8x2_t vu;
                vu.val[0] = v;
                vu.val[1] = u;
                vst2_u8(dst_row + c * 2, vu);
            }
#endif
            for (; c < chroma_width; ++c) {
                dst_row[c * 2]     = v_row[c];
                dst_row[c * 2 + 1] = u_row[c];
            }
        }
        return true;
    }

    // Fallback Path 4: General uv_pixel_stride
    for (int32_t r = 0; r < chroma_height; ++r) {
        const uint8_t* u_row = u_src + r * uv_row_stride;
        const uint8_t* v_row = v_src + r * uv_row_stride;
        uint8_t* dst_row = dst_uv + r * width;
        for (int32_t c = 0; c < chroma_width; ++c) {
            dst_row[c * 2]     = v_row[c * uv_pixel_stride];
            dst_row[c * 2 + 1] = u_row[c * uv_pixel_stride];
        }
    }
    return true;
}

struct BufferHolder {
    const uint8_t* ptr = nullptr;
    jbyteArray array = nullptr;
    jbyte* raw_bytes = nullptr;
    bool is_direct = false;
};

static bool acquireSourceBuffer(JNIEnv* env, jobject buf, BufferHolder& holder) {
    if (!buf) return false;
    void* direct_addr = env->GetDirectBufferAddress(buf);
    if (direct_addr) {
        holder.is_direct = true;
        holder.ptr = static_cast<const uint8_t*>(direct_addr) + getBufferPosition(env, buf);
        return true;
    }
    jclass bufClass = env->GetObjectClass(buf);
    jmethodID hasArrayMid = env->GetMethodID(bufClass, "hasArray", "()Z");
    if (hasArrayMid && env->CallBooleanMethod(buf, hasArrayMid)) {
        jmethodID arrayMid = env->GetMethodID(bufClass, "array", "()[B");
        jmethodID arrayOffsetMid = env->GetMethodID(bufClass, "arrayOffset", "()I");
        jbyteArray arr = static_cast<jbyteArray>(env->CallObjectMethod(buf, arrayMid));
        if (arr) {
            holder.array = arr;
            holder.raw_bytes = env->GetByteArrayElements(arr, nullptr);
            if (holder.raw_bytes) {
                jint offset = env->CallIntMethod(buf, arrayOffsetMid);
                jint pos = getBufferPosition(env, buf);
                holder.ptr = reinterpret_cast<const uint8_t*>(holder.raw_bytes) + offset + pos;
                holder.is_direct = false;
                return true;
            }
        }
    }
    return false;
}

static void releaseSourceBuffer(JNIEnv* env, BufferHolder& holder) {
    if (!holder.is_direct && holder.array && holder.raw_bytes) {
        env->ReleaseByteArrayElements(holder.array, holder.raw_bytes, JNI_ABORT);
    }
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_github_digitallyrefined_androidipcamera_helpers_NativeImagePipeline_nativeIsNeonSupported(
    JNIEnv* env,
    jclass clazz
) {
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jboolean JNICALL
Java_com_github_digitallyrefined_androidipcamera_helpers_NativeImagePipeline_nativeConvertPlanesToNv21Direct(
    JNIEnv* env,
    jclass clazz,
    jobject y_buf,
    jobject u_buf,
    jobject v_buf,
    jobject out_nv21,
    jint width,
    jint height,
    jint y_row_stride,
    jint uv_row_stride,
    jint uv_pixel_stride
) {
    if (!y_buf || !u_buf || !v_buf || !out_nv21) return JNI_FALSE;
    if (width <= 0 || height <= 0 || (width & 1) != 0 || (height & 1) != 0) return JNI_FALSE;

    void* y_base = env->GetDirectBufferAddress(y_buf);
    void* u_base = env->GetDirectBufferAddress(u_buf);
    void* v_base = env->GetDirectBufferAddress(v_buf);
    void* out_base = env->GetDirectBufferAddress(out_nv21);

    if (!y_base || !u_base || !v_base || !out_base) return JNI_FALSE;

    jlong out_cap = env->GetDirectBufferCapacity(out_nv21);
    jlong req_out_size = static_cast<jlong>(width) * height * 3 / 2;
    if (out_cap > 0 && out_cap < req_out_size) return JNI_FALSE;

    const uint8_t* y_src = static_cast<const uint8_t*>(y_base) + getBufferPosition(env, y_buf);
    const uint8_t* u_src = static_cast<const uint8_t*>(u_base) + getBufferPosition(env, u_buf);
    const uint8_t* v_src = static_cast<const uint8_t*>(v_base) + getBufferPosition(env, v_buf);
    uint8_t* dst = static_cast<uint8_t*>(out_base) + getBufferPosition(env, out_nv21);

    bool ok = convertPlanesInternal(
        y_src, u_src, v_src, dst,
        width, height,
        y_row_stride, uv_row_stride, uv_pixel_stride
    );
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_github_digitallyrefined_androidipcamera_helpers_NativeImagePipeline_nativeConvertPlanesToNv21ByteArray(
    JNIEnv* env,
    jclass clazz,
    jobject y_buf,
    jobject u_buf,
    jobject v_buf,
    jbyteArray out_nv21,
    jint width,
    jint height,
    jint y_row_stride,
    jint uv_row_stride,
    jint uv_pixel_stride
) {
    if (!y_buf || !u_buf || !v_buf || !out_nv21) return JNI_FALSE;
    if (width <= 0 || height <= 0 || (width & 1) != 0 || (height & 1) != 0) return JNI_FALSE;

    jsize out_len = env->GetArrayLength(out_nv21);
    jsize req_out_size = static_cast<jsize>(width) * height * 3 / 2;
    if (out_len < req_out_size) return JNI_FALSE;

    BufferHolder y_hold, u_hold, v_hold;
    if (!acquireSourceBuffer(env, y_buf, y_hold)) {
        return JNI_FALSE;
    }
    if (!acquireSourceBuffer(env, u_buf, u_hold)) {
        releaseSourceBuffer(env, y_hold);
        return JNI_FALSE;
    }
    if (!acquireSourceBuffer(env, v_buf, v_hold)) {
        releaseSourceBuffer(env, y_hold);
        releaseSourceBuffer(env, u_hold);
        return JNI_FALSE;
    }

    jbyte* out_raw = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(out_nv21, nullptr));
    if (!out_raw) {
        releaseSourceBuffer(env, y_hold);
        releaseSourceBuffer(env, u_hold);
        releaseSourceBuffer(env, v_hold);
        return JNI_FALSE;
    }

    bool ok = convertPlanesInternal(
        y_hold.ptr, u_hold.ptr, v_hold.ptr,
        reinterpret_cast<uint8_t*>(out_raw),
        width, height,
        y_row_stride, uv_row_stride, uv_pixel_stride
    );

    env->ReleasePrimitiveArrayCritical(out_nv21, out_raw, 0);
    releaseSourceBuffer(env, y_hold);
    releaseSourceBuffer(env, u_hold);
    releaseSourceBuffer(env, v_hold);

    return ok ? JNI_TRUE : JNI_FALSE;
}

}

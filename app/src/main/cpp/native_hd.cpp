#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <cmath>
#include <algorithm>
#include <vector>

#define LOG_TAG "FotoLiveHD"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Bilateral-like fast denoise + coring sharpen in native C++
// Input: ARGB_8888 bitmap, modified in place
// strength: 0..1, sharpen 0..1

extern "C"
JNIEXPORT jboolean JNICALL
Java_livefoto_xystudio_app_NativeHD_enhanceBitmap(JNIEnv *env, jclass clazz,
                                                  jobject bitmap,
                                                  jfloat denoiseStrength,
                                                  jfloat sharpenStrength) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("getInfo failed");
        return JNI_FALSE;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 && info.format != ANDROID_BITMAP_FORMAT_RGB_565) {
        // We need RGBA_8888
        LOGE("format not RGBA_8888: %d", info.format);
        return JNI_FALSE;
    }
    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("lockPixels failed");
        return JNI_FALSE;
    }

    int w = info.width;
    int h = info.height;
    int stride = info.stride;

    if (w < 3 || h < 3) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return JNI_TRUE;
    }

    // Copy to temp buffer for reading
    std::vector<uint32_t> src(w * h);
    for (int y = 0; y < h; ++y) {
        uint32_t *row = (uint32_t*) ((uint8_t*)pixels + y * stride);
        for (int x = 0; x < w; ++x) {
            src[y * w + x] = row[x];
        }
    }

    std::vector<uint32_t> dst(w * h);

    float sigma = 26.0f;
    float sigma2 = sigma * sigma * 2.0f;
    float denoise = std::clamp(denoiseStrength, 0.0f, 1.0f);
    float sharpen = std::clamp(sharpenStrength, 0.0f, 1.0f);

    // Fast 5x5 bilateral approximation with early exit
    for (int y = 0; y < h; ++y) {
        int y0 = std::max(0, y - 2);
        int y1 = std::min(h - 1, y + 2);
        for (int x = 0; x < w; ++x) {
            int x0 = std::max(0, x - 2);
            int x1 = std::min(w - 1, x + 2);

            uint32_t center = src[y * w + x];
            int ca = (center >> 24) & 0xFF;
            int cr = (center >> 16) & 0xFF;
            int cg = (center >> 8) & 0xFF;
            int cb = center & 0xFF;

            float sumR = 0, sumG = 0, sumB = 0, sumW = 0;

            for (int yy = y0; yy <= y1; ++yy) {
                for (int xx = x0; xx <= x1; ++xx) {
                    uint32_t p = src[yy * w + xx];
                    int pr = (p >> 16) & 0xFF;
                    int pg = (p >> 8) & 0xFF;
                    int pb = p & 0xFF;

                    int dx = xx - x;
                    int dy = yy - y;
                    float spatial = (dx == 0 && dy == 0) ? 4.0f : ((dx == 0 || dy == 0) ? 2.5f : 1.4f);

                    float dr = pr - cr;
                    float dg = pg - cg;
                    float db = pb - cb;
                    float colorDist = (dr*dr + dg*dg + db*db) / 3.0f;
                    float colorW = expf(-colorDist / sigma2);
                    float wgt = spatial * colorW;

                    sumR += pr * wgt;
                    sumG += pg * wgt;
                    sumB += pb * wgt;
                    sumW += wgt;
                }
            }

            float invW = sumW > 0.0001f ? 1.0f / sumW : 1.0f;
            float smoothR = sumR * invW;
            float smoothG = sumG * invW;
            float smoothB = sumB * invW;

            float baseR = cr + (smoothR - cr) * denoise * 0.70f;
            float baseG = cg + (smoothG - cg) * denoise * 0.70f;
            float baseB = cb + (smoothB - cb) * denoise * 0.70f;

            if (sharpen > 0.01f) {
                // simple 4-neighbour blur for unsharp
                int top = src[std::max(0, y - 1) * w + x];
                int btm = src[std::min(h - 1, y + 1) * w + x];
                int lft = src[y * w + std::max(0, x - 1)];
                int rgt = src[y * w + std::min(w - 1, x + 1)];

                float blurR = ((top >> 16 & 0xFF) + (btm >> 16 & 0xFF) + (lft >> 16 & 0xFF) + (rgt >> 16 & 0xFF)) * 0.25f;
                float blurG = ((top >> 8 & 0xFF) + (btm >> 8 & 0xFF) + (lft >> 8 & 0xFF) + (rgt >> 8 & 0xFF)) * 0.25f;
                float blurB = ((top & 0xFF) + (btm & 0xFF) + (lft & 0xFF) + (rgt & 0xFF)) * 0.25f;

                float diffR = baseR - blurR;
                float diffG = baseG - blurG;
                float diffB = baseB - blurB;
                float luma = fabsf(0.299f * diffR + 0.587f * diffG + 0.114f * diffB);
                if (luma > 6.0f) {
                    float coring = std::clamp((luma - 6.0f) / 12.0f, 0.0f, 1.0f);
                    baseR += diffR * sharpen * 0.65f * coring;
                    baseG += diffG * sharpen * 0.65f * coring;
                    baseB += diffB * sharpen * 0.65f * coring;
                }
            }

            int fr = std::clamp((int)baseR, 0, 255);
            int fg = std::clamp((int)baseG, 0, 255);
            int fb = std::clamp((int)baseB, 0, 255);
            dst[y * w + x] = (ca << 24) | (fr << 16) | (fg << 8) | fb;
        }
    }

    // Write back
    for (int y = 0; y < h; ++y) {
        uint32_t *row = (uint32_t*) ((uint8_t*)pixels + y * stride);
        for (int x = 0; x < w; ++x) {
            row[x] = dst[y * w + x];
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    LOGI("enhance done %dx%d denoise=%.2f sharpen=%.2f", w, h, denoise, sharpen);
    return JNI_TRUE;
}

// Extra native: calculate optimal bitrate for HD based on resolution
extern "C"
JNIEXPORT jint JNICALL
Java_livefoto_xystudio_app_NativeHD_calcBitrate(JNIEnv *env, jclass clazz,
                                                jint width, jint height) {
    long pixels = (long)width * height;
    int bitrate;
    if (pixels >= 1920L * 1080L) {
        bitrate = (int)(pixels * 6);
        if (bitrate < 8000000) bitrate = 8000000;
        if (bitrate > 25000000) bitrate = 25000000;
    } else if (pixels >= 1280L * 720L) {
        bitrate = (int)(pixels * 7);
        if (bitrate < 6000000) bitrate = 6000000;
        if (bitrate > 16000000) bitrate = 16000000;
    } else {
        bitrate = (int)(pixels * 8);
        if (bitrate < 3000000) bitrate = 3000000;
        if (bitrate > 12000000) bitrate = 12000000;
    }
    return bitrate;
}

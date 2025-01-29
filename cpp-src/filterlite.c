#include <jni.h>
#include <stdlib.h>
#include <string.h>

#ifdef __APPLE__
  #define USE_STANDARD_C
  #undef USE_X86_64_ASM
  #undef USE_AARCH64_ASM
#endif

#if defined(_WIN32) || defined(_WIN64)
  #define USE_STANDARD_C
  #undef USE_X86_64_ASM
  #undef USE_AARCH64_ASM
#elif defined(__x86_64__) || defined(_M_X64)
  #define USE_X86_64_ASM
#elif defined(__aarch64__) || defined(_M_ARM64)
  #define USE_AARCH64_ASM
#endif

#include "filterlite_asm.h"

JNIEXPORT jbyteArray JNICALL Java_me_brandonli_mcav_media_player_pipeline_filter_video_dither_algorithm_error_FilterLiteDither_ditherNatively
  (JNIEnv *env, jobject obj, jintArray buffer, jint width, jintArray colors, jbyteArray mapColors) {

  jint *bufferPtr = (*env)->GetIntArrayElements(env, buffer, NULL);
  jint *colorsPtr = (*env)->GetIntArrayElements(env, colors, NULL);
  jbyte *mapColorsPtr = (*env)->GetByteArrayElements(env, mapColors, NULL);

  jsize bufferLen = (*env)->GetArrayLength(env, buffer);
  jint height = bufferLen / width;
  jint widthMinus = width - 1;
  jint heightMinus = height - 1;

  int **ditherBuffer = (int **)malloc(2 * sizeof(int *));
  for (int i = 0; i < 2; i++) {
    ditherBuffer[i] = (int *)malloc((width << 2) * sizeof(int));
    memset(ditherBuffer[i], 0, (width << 2) * sizeof(int));
  }

  jbyteArray result = (*env)->NewByteArray(env, bufferLen);
  jbyte *resultPtr = (*env)->GetByteArrayElements(env, result, NULL);

  for (int y = 0; y < height; y++) {
    jboolean hasNextY = y < heightMinus;
    jint yIndex = y * width;

    if ((y & 0x1) == 0) {
#ifdef USE_X86_64_ASM
      process_even_row_x86_64(bufferPtr, colorsPtr, mapColorsPtr, resultPtr,
                             ditherBuffer[0], ditherBuffer[1],
                             width, widthMinus, yIndex, hasNextY);
#elif defined(USE_AARCH64_ASM)
      process_even_row_aarch64(bufferPtr, colorsPtr, mapColorsPtr, resultPtr,
                              ditherBuffer[0], ditherBuffer[1],
                              width, widthMinus, yIndex, hasNextY);
#else
      int bufferIndex = 0;
      int *buf1 = ditherBuffer[0];
      int *buf2 = ditherBuffer[1];
      for (int x = 0; x < width; ++x) {
        jint index = yIndex + x;
        jint rgb = bufferPtr[index];
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        red = red + buf1[bufferIndex];
        red = (red | ((255 - red) >> 31)) & (red | ((red - 256) >> 31));
        bufferIndex++;
        green = green + buf1[bufferIndex];
        green = (green | ((255 - green) >> 31)) & (green | ((green - 256) >> 31));
        bufferIndex++;
        blue = blue + buf1[bufferIndex];
        blue = (blue | ((255 - blue) >> 31)) & (blue | ((blue - 256) >> 31));
        bufferIndex++;
        int colorIndex = ((red >> 1) << 14) | ((green >> 1) << 7) | (blue >> 1);
        int closest = colorsPtr[colorIndex];
        int r = (closest >> 16) & 0xFF;
        int g = (closest >> 8) & 0xFF;
        int b = closest & 0xFF;
        int delta_r = red - r;
        int delta_g = green - g;
        int delta_b = blue - b;
        int xMask = -((x < widthMinus) ? 1 : 0);
        buf1[bufferIndex] = (delta_r >> 1) & xMask;
        buf1[bufferIndex + 1] = (delta_g >> 1) & xMask;
        buf1[bufferIndex + 2] = (delta_b >> 1) & xMask;
        if (hasNextY) {
          int prevXMask = -((x > 0) ? 1 : 0);
          buf2[bufferIndex - 6] = (delta_r >> 2) & prevXMask;
          buf2[bufferIndex - 5] = (delta_g >> 2) & prevXMask;
          buf2[bufferIndex - 4] = (delta_b >> 2) & prevXMask;
          buf2[bufferIndex - 3] = delta_r >> 2;
          buf2[bufferIndex - 2] = delta_g >> 2;
          buf2[bufferIndex - 1] = delta_b >> 2;
        }
        jint mapColorIndex = ((r >> 1) << 14) | ((g >> 1) << 7) | (b >> 1);
        resultPtr[index] = mapColorsPtr[mapColorIndex];
      }
#endif
    } else {
#ifdef USE_X86_64_ASM
      process_odd_row_x86_64(bufferPtr, colorsPtr, mapColorsPtr, resultPtr,
                            ditherBuffer[1], ditherBuffer[0],
                            width, widthMinus, yIndex, hasNextY);
#elif defined(USE_AARCH64_ASM)
      process_odd_row_aarch64(bufferPtr, colorsPtr, mapColorsPtr, resultPtr,
                             ditherBuffer[1], ditherBuffer[0],
                             width, widthMinus, yIndex, hasNextY);
#else
      int bufferIndex = (width << 2) - 3;
      int *buf1 = ditherBuffer[1];
      int *buf2 = ditherBuffer[0];
      for (int x = width - 1; x >= 0; --x) {
        jint index = yIndex + x;
        jint rgb = bufferPtr[index];
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        red = red + buf1[bufferIndex];
        red = (red | ((255 - red) >> 31)) & (red | ((red - 256) >> 31));
        green = green + buf1[bufferIndex + 1];
        green = (green | ((255 - green) >> 31)) & (green | ((green - 256) >> 31));
        blue = blue + buf1[bufferIndex + 2];
        blue = (blue | ((255 - blue) >> 31)) & (blue | ((blue - 256) >> 31));
        int colorIndex = ((red >> 1) << 14) | ((green >> 1) << 7) | (blue >> 1);
        int closest = colorsPtr[colorIndex];
        int r = (closest >> 16) & 0xFF;
        int g = (closest >> 8) & 0xFF;
        int b = closest & 0xFF;
        int delta_r = red - r;
        int delta_g = green - g;
        int delta_b = blue - b;
        bufferIndex -= 3;
        int xMask = -((x > 0) ? 1 : 0);
        buf1[bufferIndex + 3] = (delta_r >> 1) & xMask;
        buf1[bufferIndex + 4] = (delta_g >> 1) & xMask;
        buf1[bufferIndex + 5] = (delta_b >> 1) & xMask;
        if (hasNextY) {
          int nextXMask = -((x < widthMinus) ? 1 : 0);
          buf2[bufferIndex + 6] = (delta_r >> 2) & nextXMask;
          buf2[bufferIndex + 7] = (delta_g >> 2) & nextXMask;
          buf2[bufferIndex + 8] = (delta_b >> 2) & nextXMask;
          buf2[bufferIndex + 3] = delta_r >> 2;
          buf2[bufferIndex + 4] = delta_g >> 2;
          buf2[bufferIndex + 5] = delta_b >> 2;
        }
        jint mapColorIndex = ((r >> 1) << 14) | ((g >> 1) << 7) | (b >> 1);
        resultPtr[index] = mapColorsPtr[mapColorIndex];
      }
#endif
    }
  }
  (*env)->ReleaseIntArrayElements(env, buffer, bufferPtr, JNI_ABORT);
  (*env)->ReleaseIntArrayElements(env, colors, colorsPtr, JNI_ABORT);
  (*env)->ReleaseByteArrayElements(env, mapColors, mapColorsPtr, JNI_ABORT);
  (*env)->ReleaseByteArrayElements(env, result, resultPtr, 0);
  for (int i = 0; i < 2; i++) {
    free(ditherBuffer[i]);
  }
  free(ditherBuffer);

  return result;
}
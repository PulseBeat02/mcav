#include <jni.h>
#include <stdlib.h>
#include <string.h>

// Architecture detection
#if defined(__x86_64__) || defined(_M_X64)
#define USE_X86_64_ASM
#elif defined(__aarch64__) || defined(_M_ARM64)
#define USE_AARCH64_ASM
#endif

JNIEXPORT jbyteArray JNICALL Java_me_brandonli_mcav_media_video_dither_algorithm_error_FilterLiteDither_ditherNatively
  (JNIEnv *env, jobject obj, jintArray buffer, jint width, jintArray colors, jbyteArray mapColors) {

  // Get the arrays from Java
  jint *bufferPtr = (*env)->GetIntArrayElements(env, buffer, NULL);
  jint *colorsPtr = (*env)->GetIntArrayElements(env, colors, NULL);
  jbyte *mapColorsPtr = (*env)->GetByteArrayElements(env, mapColors, NULL);

  // Get array length and calculate dimensions
  jsize bufferLen = (*env)->GetArrayLength(env, buffer);
  jint height = bufferLen / width;
  jint widthMinus = width - 1;
  jint heightMinus = height - 1;

  // Allocate dither buffers
  int **ditherBuffer = (int **)malloc(2 * sizeof(int *));
  for (int i = 0; i < 2; i++) {
    ditherBuffer[i] = (int *)malloc((width << 2) * sizeof(int));
    memset(ditherBuffer[i], 0, (width << 2) * sizeof(int));
  }

  // Create result array
  jbyteArray result = (*env)->NewByteArray(env, bufferLen);
  jbyte *resultPtr = (*env)->GetByteArrayElements(env, result, NULL);

  // Perform dithering algorithm
  for (int y = 0; y < height; y++) {
    jboolean hasNextY = y < heightMinus;
    jint yIndex = y * width;

    if ((y & 0x1) == 0) {
      int bufferIndex = 0;
      int *buf1 = ditherBuffer[0];
      int *buf2 = ditherBuffer[1];

      for (int x = 0; x < width; ++x) {
        jint index = yIndex + x;
        jint rgb = bufferPtr[index];

#ifdef USE_X86_64_ASM
        // Extract and adjust RGB using x86_64 assembly
        int red, green, blue, colorIndex, closest;
        __asm__ volatile (
            // Extract RGB components
            "movl %4, %%eax\n"
            "movl %%eax, %%ebx\n"
            "movl %%eax, %%ecx\n"
            "shrl $16, %%eax\n"      // red = (rgb >> 16) & 0xFF
            "andl $0xFF, %%eax\n"
            "shrl $8, %%ebx\n"       // green = (rgb >> 8) & 0xFF
            "andl $0xFF, %%ebx\n"
            "andl $0xFF, %%ecx\n"    // blue = rgb & 0xFF

            // Apply dither buffer adjustments and clamp
            "addl %5, %%eax\n"       // red += buf1[bufferIndex]
            "movl %%eax, %%edx\n"
            "negl %%edx\n"
            "sarl $31, %%edx\n"
            "andl %%edx, %%eax\n"    // Clamp lower bound to 0
            "movl %%eax, %%edx\n"
            "subl $256, %%edx\n"
            "sarl $31, %%edx\n"
            "notl %%edx\n"
            "andl %%edx, %%eax\n"    // Clamp upper bound to 255
            "movl %%eax, %0\n"       // Store to red

            "addl %6, %%ebx\n"       // green += buf1[bufferIndex+1]
            "movl %%ebx, %%edx\n"
            "negl %%edx\n"
            "sarl $31, %%edx\n"
            "andl %%edx, %%ebx\n"
            "movl %%ebx, %%edx\n"
            "subl $256, %%edx\n"
            "sarl $31, %%edx\n"
            "notl %%edx\n"
            "andl %%edx, %%ebx\n"
            "movl %%ebx, %1\n"       // Store to green

            "addl %7, %%ecx\n"       // blue += buf1[bufferIndex+2]
            "movl %%ecx, %%edx\n"
            "negl %%edx\n"
            "sarl $31, %%edx\n"
            "andl %%edx, %%ecx\n"
            "movl %%ecx, %%edx\n"
            "subl $256, %%edx\n"
            "sarl $31, %%edx\n"
            "notl %%edx\n"
            "andl %%edx, %%ecx\n"
            "movl %%ecx, %2\n"       // Store to blue

            // Calculate color index
            "movl %0, %%eax\n"       // Load red
            "sarl $1, %%eax\n"       // red >> 1
            "shll $14, %%eax\n"      // (red >> 1) << 14
            "movl %1, %%ebx\n"       // Load green
            "sarl $1, %%ebx\n"       // green >> 1
            "shll $7, %%ebx\n"       // (green >> 1) << 7
            "orl %%ebx, %%eax\n"     // | green component
            "movl %2, %%ecx\n"       // Load blue
            "sarl $1, %%ecx\n"       // blue >> 1
            "orl %%ecx, %%eax\n"     // | blue component
            "movl %%eax, %3\n"       // Store to colorIndex
            : "=r"(red), "=r"(green), "=r"(blue), "=r"(colorIndex)
            : "r"(rgb), "r"(buf1[bufferIndex]), "r"(buf1[bufferIndex+1]), "r"(buf1[bufferIndex+2])
            : "eax", "ebx", "ecx", "edx"
        );

        closest = colorsPtr[colorIndex];

        // Extract RGB from closest color using assembly
        int r, g, b;
        __asm__ volatile (
            "movl %3, %%eax\n"
            "shrl $16, %%eax\n"
            "andl $0xFF, %%eax\n"
            "movl %%eax, %0\n"

            "movl %3, %%eax\n"
            "shrl $8, %%eax\n"
            "andl $0xFF, %%eax\n"
            "movl %%eax, %1\n"

            "movl %3, %%eax\n"
            "andl $0xFF, %%eax\n"
            "movl %%eax, %2\n"
            : "=r"(r), "=r"(g), "=r"(b)
            : "r"(closest)
            : "eax"
        );
#elif defined(USE_AARCH64_ASM)
        // Extract and adjust RGB using AArch64 assembly
        int red, green, blue, colorIndex, closest;
        __asm__ volatile (
            // Extract RGB components
            "mov w8, %w4\n"          // w8 = rgb
            "lsr w9, w8, #16\n"      // red = (rgb >> 16)
            "and w9, w9, #0xFF\n"    // red &= 0xFF
            "lsr w10, w8, #8\n"      // green = (rgb >> 8)
            "and w10, w10, #0xFF\n"  // green &= 0xFF
            "and w11, w8, #0xFF\n"   // blue = rgb & 0xFF

            // Apply dither buffer adjustments and clamp
            "ldr w12, [%5]\n"        // w12 = buf1[bufferIndex]
            "add w9, w9, w12\n"      // red += buf1[bufferIndex]

            // Clamp red to 0-255
            "cmp w9, #0\n"           // Compare with 0
            "csel w9, wzr, w9, lt\n" // If less than 0, use 0
            "cmp w9, #255\n"         // Compare with 255
            "mov w12, #255\n"        // Set w12 to 255
            "csel w9, w12, w9, gt\n" // If greater than 255, use 255

            "ldr w12, [%5, #4]\n"    // w12 = buf1[bufferIndex+1]
            "add w10, w10, w12\n"    // green += buf1[bufferIndex+1]

            // Clamp green to 0-255
            "cmp w10, #0\n"
            "csel w10, wzr, w10, lt\n"
            "cmp w10, #255\n"
            "mov w12, #255\n"
            "csel w10, w12, w10, gt\n"

            "ldr w12, [%5, #8]\n"    // w12 = buf1[bufferIndex+2]
            "add w11, w11, w12\n"    // blue += buf1[bufferIndex+2]

            // Clamp blue to 0-255
            "cmp w11, #0\n"
            "csel w11, wzr, w11, lt\n"
            "cmp w11, #255\n"
            "mov w12, #255\n"
            "csel w11, w12, w11, gt\n"

            // Calculate color index
            "lsr w12, w9, #1\n"      // red >> 1
            "lsl w12, w12, #14\n"    // (red >> 1) << 14
            "lsr w13, w10, #1\n"     // green >> 1
            "lsl w13, w13, #7\n"     // (green >> 1) << 7
            "orr w12, w12, w13\n"    // | green component
            "lsr w13, w11, #1\n"     // blue >> 1
            "orr w12, w12, w13\n"    // | blue component

            // Store results
            "mov %w0, w9\n"          // red
            "mov %w1, w10\n"         // green
            "mov %w2, w11\n"         // blue
            "mov %w3, w12\n"         // colorIndex
            : "=r"(red), "=r"(green), "=r"(blue), "=r"(colorIndex)
            : "r"(rgb), "r"(&buf1[bufferIndex])
            : "w8", "w9", "w10", "w11", "w12", "w13", "memory"
        );

        closest = colorsPtr[colorIndex];

        // Extract RGB from closest color using AArch64 assembly
        int r, g, b;
        __asm__ volatile (
            "mov w8, %w3\n"          // w8 = closest
            "lsr w9, w8, #16\n"      // r = (closest >> 16)
            "and %w0, w9, #0xFF\n"   // r &= 0xFF

            "lsr w9, w8, #8\n"       // g = (closest >> 8)
            "and %w1, w9, #0xFF\n"   // g &= 0xFF

            "and %w2, w8, #0xFF\n"   // b = closest & 0xFF
            : "=r"(r), "=r"(g), "=r"(b)
            : "r"(closest)
            : "w8", "w9"
        );
#else
        // Standard C implementation for other platforms
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;

        red = red + buf1[bufferIndex++];
        red = (red | ((255 - red) >> 31)) & (red | ((red - 256) >> 31));
        green = green + buf1[bufferIndex++];
        green = (green | ((255 - green) >> 31)) & (green | ((green - 256) >> 31));
        blue = blue + buf1[bufferIndex++];
        blue = (blue | ((255 - blue) >> 31)) & (blue | ((blue - 256) >> 31));

        jint colorIndex = ((red >> 1) << 14) | ((green >> 1) << 7) | (blue >> 1);
        jint closest = colorsPtr[colorIndex];

        int r = (closest >> 16) & 0xFF;
        int g = (closest >> 8) & 0xFF;
        int b = closest & 0xFF;
#endif

#ifdef USE_X86_64_ASM
        // Calculate error deltas using x86_64 assembly
        int delta_r, delta_g, delta_b;
        __asm__ volatile (
            "movl %3, %%eax\n"
            "subl %6, %%eax\n"
            "movl %%eax, %0\n"

            "movl %4, %%eax\n"
            "subl %7, %%eax\n"
            "movl %%eax, %1\n"

            "movl %5, %%eax\n"
            "subl %8, %%eax\n"
            "movl %%eax, %2\n"
            : "=r"(delta_r), "=r"(delta_g), "=r"(delta_b)
            : "r"(red), "r"(green), "r"(blue), "r"(r), "r"(g), "r"(b)
            : "eax"
        );

        // The rest of the buffer updating code uses standard C
        bufferIndex += 3;
#elif defined(USE_AARCH64_ASM)
        // Calculate error deltas using AArch64 assembly
        int delta_r, delta_g, delta_b;
        __asm__ volatile (
            "sub %w0, %w3, %w6\n"    // delta_r = red - r
            "sub %w1, %w4, %w7\n"    // delta_g = green - g
            "sub %w2, %w5, %w8\n"    // delta_b = blue - b
            : "=r"(delta_r), "=r"(delta_g), "=r"(delta_b)
            : "r"(red), "r"(green), "r"(blue), "r"(r), "r"(g), "r"(b)
        );

        bufferIndex += 3;
#else
        int delta_r = red - r;
        int delta_g = green - g;
        int delta_b = blue - b;
#endif

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
    } else {
      int bufferIndex = (width << 2) - 3;
      int *buf1 = ditherBuffer[1];
      int *buf2 = ditherBuffer[0];

      for (int x = width - 1; x >= 0; --x) {
        jint index = yIndex + x;
        jint rgb = bufferPtr[index];

#ifdef USE_AARCH64_ASM
        // AArch64 implementation for odd rows
        int red, green, blue, colorIndex, closest;
        __asm__ volatile (
            // Extract RGB components
            "mov w8, %w4\n"
            "lsr w9, w8, #16\n"
            "and w9, w9, #0xFF\n"
            "lsr w10, w8, #8\n"
            "and w10, w10, #0xFF\n"
            "and w11, w8, #0xFF\n"

            // Apply dither buffer adjustments and clamp
            "ldr w12, [%5]\n"
            "add w9, w9, w12\n"
            "cmp w9, #0\n"
            "csel w9, wzr, w9, lt\n"
            "cmp w9, #255\n"
            "mov w12, #255\n"
            "csel w9, w12, w9, gt\n"

            "ldr w12, [%5, #4]\n"
            "add w10, w10, w12\n"
            "cmp w10, #0\n"
            "csel w10, wzr, w10, lt\n"
            "cmp w10, #255\n"
            "mov w12, #255\n"
            "csel w10, w12, w10, gt\n"

            "ldr w12, [%5, #8]\n"
            "add w11, w11, w12\n"
            "cmp w11, #0\n"
            "csel w11, wzr, w11, lt\n"
            "cmp w11, #255\n"
            "mov w12, #255\n"
            "csel w11, w12, w11, gt\n"

            // Calculate color index
            "lsr w12, w9, #1\n"
            "lsl w12, w12, #14\n"
            "lsr w13, w10, #1\n"
            "lsl w13, w13, #7\n"
            "orr w12, w12, w13\n"
            "lsr w13, w11, #1\n"
            "orr w12, w12, w13\n"

            // Store results
            "mov %w0, w9\n"
            "mov %w1, w10\n"
            "mov %w2, w11\n"
            "mov %w3, w12\n"
            : "=r"(red), "=r"(green), "=r"(blue), "=r"(colorIndex)
            : "r"(rgb), "r"(&buf1[bufferIndex])
            : "w8", "w9", "w10", "w11", "w12", "w13", "memory"
        );

        closest = colorsPtr[colorIndex];

        // Extract RGB from closest color
        int r, g, b;
        __asm__ volatile (
            "mov w8, %w3\n"
            "lsr w9, w8, #16\n"
            "and %w0, w9, #0xFF\n"
            "lsr w9, w8, #8\n"
            "and %w1, w9, #0xFF\n"
            "and %w2, w8, #0xFF\n"
            : "=r"(r), "=r"(g), "=r"(b)
            : "r"(closest)
            : "w8", "w9"
        );

        // Calculate error deltas
        int delta_r, delta_g, delta_b;
        __asm__ volatile (
            "sub %w0, %w3, %w6\n"
            "sub %w1, %w4, %w7\n"
            "sub %w2, %w5, %w8\n"
            : "=r"(delta_r), "=r"(delta_g), "=r"(delta_b)
            : "r"(red), "r"(green), "r"(blue), "r"(r), "r"(g), "r"(b)
        );

        bufferIndex -= 3;
#else
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;

        red = red + buf1[bufferIndex];
        red = (red | ((255 - red) >> 31)) & (red | ((red - 256) >> 31));
        green = green + buf1[bufferIndex + 1];
        green = (green | ((255 - green) >> 31)) & (green | ((green - 256) >> 31));
        blue = blue + buf1[bufferIndex + 2];
        blue = (blue | ((255 - blue) >> 31)) & (blue | ((blue - 256) >> 31));
        bufferIndex -= 3;

        jint colorIndex = ((red >> 1) << 14) | ((green >> 1) << 7) | (blue >> 1);
        jint closest = colorsPtr[colorIndex];

        int r = (closest >> 16) & 0xFF;
        int g = (closest >> 8) & 0xFF;
        int b = closest & 0xFF;

        int delta_r = red - r;
        int delta_g = green - g;
        int delta_b = blue - b;
#endif

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
    }
  }

  // Release resources
  (*env)->ReleaseIntArrayElements(env, buffer, bufferPtr, JNI_ABORT);
  (*env)->ReleaseIntArrayElements(env, colors, colorsPtr, JNI_ABORT);
  (*env)->ReleaseByteArrayElements(env, mapColors, mapColorsPtr, JNI_ABORT);
  (*env)->ReleaseByteArrayElements(env, result, resultPtr, 0);

  // Free allocated memory
  for (int i = 0; i < 2; i++) {
    free(ditherBuffer[i]);
  }
  free(ditherBuffer);

  return result;
}
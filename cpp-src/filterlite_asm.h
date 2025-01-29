// filterlite_asm.h
#ifndef FILTERLITE_ASM_H
#define FILTERLITE_ASM_H

#ifndef _JAVASOFT_JNI_H_
typedef int jint;
typedef signed char jbyte;
typedef int jboolean;
#endif

#ifdef __cplusplus
extern "C" {
#endif

#if defined(__APPLE__)
void __process_even_row_x86_64(jint* bufferPtr, jint* colorsPtr, jbyte* mapColorsPtr, jbyte* resultPtr,
                         int* buf1, int* buf2, jint width, jint widthMinus, jint yIndex, jboolean hasNextY) {
}

void __process_odd_row_x86_64(jint* bufferPtr, jint* colorsPtr, jbyte* mapColorsPtr, jbyte* resultPtr,
                        int* buf1, int* buf2, jint width, jint widthMinus, jint yIndex, jboolean hasNextY) {
}

void __process_even_row_aarch64(jint* bufferPtr, jint* colorsPtr, jbyte* mapColorsPtr, jbyte* resultPtr,
                         int* buf1, int* buf2, jint width, jint widthMinus, jint yIndex, jboolean hasNextY) {
}

void __process_odd_row_aarch64(jint* bufferPtr, jint* colorsPtr, jbyte* mapColorsPtr, jbyte* resultPtr,
                        int* buf1, int* buf2, jint width, jint widthMinus, jint yIndex, jboolean hasNextY) {
}

#define ASM_SYMBOL(name) _##name
#else
extern void __process_even_row_x86_64(jint* bufferPtr, jint* colorsPtr, jbyte* mapColorsPtr, jbyte* resultPtr,
                                int* buf1, int* buf2, jint width, jint widthMinus, jint yIndex, jboolean hasNextY);

extern void __process_odd_row_x86_64(jint* bufferPtr, jint* colorsPtr, jbyte* mapColorsPtr, jbyte* resultPtr,
                               int* buf1, int* buf2, jint width, jint widthMinus, jint yIndex, jboolean hasNextY);

extern void __process_even_row_aarch64(jint* bufferPtr, jint* colorsPtr, jbyte* mapColorsPtr, jbyte* resultPtr,
                                int* buf1, int* buf2, jint width, jint widthMinus, jint yIndex, jboolean hasNextY);

extern void __process_odd_row_aarch64(jint* bufferPtr, jint* colorsPtr, jbyte* mapColorsPtr, jbyte* resultPtr,
                               int* buf1, int* buf2, jint width, jint widthMinus, jint yIndex, jboolean hasNextY);

#define ASM_SYMBOL(name) name
#endif

#define process_even_row_x86_64 __process_even_row_x86_64
#define process_odd_row_x86_64 __process_odd_row_x86_64
#define process_even_row_aarch64 __process_even_row_aarch64
#define process_odd_row_aarch64 __process_odd_row_aarch64

#ifdef __cplusplus
}
#endif

#endif // FILTERLITE_ASM_H
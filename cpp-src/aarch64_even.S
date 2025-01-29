// aarch64_even.S
.global process_pixel_aarch64_even
.global extract_rgb_aarch64
.global calculate_delta_aarch64

// void process_pixel_aarch64_even(int rgb, int* buf1, int* red, int* green, int* blue, int* colorIndex)
// x0 = rgb, x1 = buf1, x2 = red, x3 = green, x4 = blue, x5 = colorIndex
process_pixel_aarch64_even:
    // Extract RGB components
    mov     w8, w0              // w8 = rgb
    lsr     w9, w8, #16         // red = (rgb >> 16)
    and     w9, w9, #0xFF       // red &= 0xFF
    lsr     w10, w8, #8         // green = (rgb >> 8)
    and     w10, w10, #0xFF     // green &= 0xFF
    and     w11, w8, #0xFF      // blue = rgb & 0xFF

    // Apply dither buffer adjustments and clamp
    ldr     w12, [x1]           // w12 = buf1[0]
    add     w9, w9, w12         // red += buf1[0]

    // Clamp red to 0-255
    cmp     w9, #0
    csel    w9, wzr, w9, lt     // If less than 0, use 0
    cmp     w9, #255
    mov     w12, #255
    csel    w9, w12, w9, gt     // If greater than 255, use 255

    ldr     w12, [x1, #4]       // w12 = buf1[1]
    add     w10, w10, w12       // green += buf1[1]

    // Clamp green to 0-255
    cmp     w10, #0
    csel    w10, wzr, w10, lt
    cmp     w10, #255
    mov     w12, #255
    csel    w10, w12, w10, gt

    ldr     w12, [x1, #8]       // w12 = buf1[2]
    add     w11, w11, w12       // blue += buf1[2]

    // Clamp blue to 0-255
    cmp     w11, #0
    csel    w11, wzr, w11, lt
    cmp     w11, #255
    mov     w12, #255
    csel    w11, w12, w11, gt

    // Calculate color index
    lsr     w12, w9, #1         // red >> 1
    lsl     w12, w12, #14       // (red >> 1) << 14
    lsr     w13, w10, #1        // green >> 1
    lsl     w13, w13, #7        // (green >> 1) << 7
    orr     w12, w12, w13       // | green component
    lsr     w13, w11, #1        // blue >> 1
    orr     w12, w12, w13       // | blue component

    // Store results
    str     w9, [x2]            // *red = red
    str     w10, [x3]           // *green = green
    str     w11, [x4]           // *blue = blue
    str     w12, [x5]           // *colorIndex = colorIndex

    ret

// void extract_rgb_aarch64(int closest, int* r, int* g, int* b)
// x0 = closest, x1 = r, x2 = g, x3 = b
extract_rgb_aarch64:
    mov     w8, w0              // w8 = closest
    lsr     w9, w8, #16         // r = (closest >> 16)
    and     w9, w9, #0xFF       // r &= 0xFF
    str     w9, [x1]            // *r = r

    lsr     w9, w8, #8          // g = (closest >> 8)
    and     w9, w9, #0xFF       // g &= 0xFF
    str     w9, [x2]            // *g = g

    and     w9, w8, #0xFF       // b = closest & 0xFF
    str     w9, [x3]            // *b = b

    ret

// void calculate_delta_aarch64(int red, int green, int blue, int r, int g, int b,
//                              int* delta_r, int* delta_g, int* delta_b)
// x0 = red, x1 = green, x2 = blue, x3 = r, x4 = g, x5 = b, x6 = delta_r, x7 = delta_g, [sp] = delta_b
calculate_delta_aarch64:
    // Set up stack frame
    stp     x29, x30, [sp, #-16]!  // Save frame pointer and link register
    mov     x29, sp                 // Set frame pointer

    // Calculate and store delta_r
    sub     w8, w0, w3              // delta_r = red - r
    str     w8, [x6]                // *delta_r = delta_r

    // Calculate and store delta_g
    sub     w8, w1, w4              // delta_g = green - g
    str     w8, [x7]                // *delta_g = delta_g

    // Calculate and store delta_b
    ldr     x8, [sp, #16]          // x8 = delta_b pointer (load from stack)
    sub     w9, w2, w5              // delta_b = blue - b
    str     w9, [x8]                // *delta_b = delta_b

    // Restore stack frame
    ldp     x29, x30, [sp], #16     // Restore frame pointer and link register
    ret
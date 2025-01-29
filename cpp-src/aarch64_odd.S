// aarch64_odd.S
.global process_pixel_aarch64_odd

// void process_pixel_aarch64_odd(int rgb, int* buf1, int* red, int* green, int* blue, int* colorIndex)
// x0 = rgb, x1 = buf1, x2 = red, x3 = green, x4 = blue, x5 = colorIndex
process_pixel_aarch64_odd:
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
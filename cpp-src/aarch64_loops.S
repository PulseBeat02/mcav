// aarch64_loops.S
.global process_even_row_aarch64
.global process_odd_row_aarch64
.extern process_pixel_aarch64_even
.extern process_pixel_aarch64_odd
.extern extract_rgb_aarch64
.extern calculate_delta_aarch64

// void process_even_row_aarch64(jint* bufferPtr, jint* colorsPtr, jbyte* mapColorsPtr, jbyte* resultPtr,
//                              int* buf1, int* buf2, jint width, jint widthMinus, jint yIndex, jboolean hasNextY)
// x0 = bufferPtr
// x1 = colorsPtr
// x2 = mapColorsPtr
// x3 = resultPtr
// x4 = buf1
// x5 = buf2
// x6 = width
// x7 = widthMinus
// [sp] = yIndex
// [sp+8] = hasNextY
process_even_row_aarch64:
    // Allocate stack frame and save registers
    stp     x29, x30, [sp, #-128]!  // Save frame pointer and link register, allocate frame
    mov     x29, sp                  // Set frame pointer

    // Save callee-saved registers
    stp     x19, x20, [sp, #16]
    stp     x21, x22, [sp, #32]
    stp     x23, x24, [sp, #48]
    stp     x25, x26, [sp, #64]
    stp     x27, x28, [sp, #80]

    // Save parameters to callee-saved registers
    mov     x19, x4                  // buf1
    mov     x20, x5                  // buf2
    mov     x21, x6                  // width
    mov     x22, x7                  // widthMinus
    ldr     x23, [x29, #128]         // yIndex
    ldr     w24, [x29, #136]         // hasNextY

    // Store remaining parameters in frame
    str     x0, [x29, #96]           // bufferPtr
    str     x1, [x29, #104]          // colorsPtr
    str     x2, [x29, #112]          // mapColorsPtr
    str     x3, [x29, #120]          // resultPtr

    // Initialize loop variables
    mov     w25, #0                  // x = 0
    mov     w26, #0                  // bufferIndex = 0
    mov     w27, #-1                 // initialize w27 for mask (all bits set)

    // Allocate space for local variables
    sub     sp, sp, #48              // Space for red, green, blue, colorIndex, r, g, b, delta_r, delta_g, delta_b

    // Start the loop
.loop_even:
    // Calculate index and load rgb
    add     x9, x23, x25              // index = yIndex + x
    ldr     x0, [x29, #96]            // bufferPtr
    ldr     w0, [x0, x9, lsl #2]      // rgb = bufferPtr[index]

    // Process pixel
    add     x1, x19, x26, lsl #2      // &buf1[bufferIndex]
    mov     x2, sp                    // &red
    add     x3, sp, #4                // &green
    add     x4, sp, #8                // &blue
    add     x5, sp, #12               // &colorIndex
    bl      process_pixel_aarch64_even

    // Get colorIndex and load closest color
    ldr     w9, [sp, #12]             // colorIndex
    ldr     x0, [x29, #104]           // colorsPtr
    ldr     w10, [x0, x9, lsl #2]     // closest = colorsPtr[colorIndex]

    // Extract RGB components from closest color
    mov     w0, w10                   // closest
    add     x1, sp, #16               // &r
    add     x2, sp, #20               // &g
    add     x3, sp, #24               // &b
    bl      extract_rgb_aarch64

    // Calculate delta
    ldr     w0, [sp]                  // red
    ldr     w1, [sp, #4]              // green
    ldr     w2, [sp, #8]              // blue
    ldr     w3, [sp, #16]             // r
    ldr     w4, [sp, #20]             // g
    ldr     w5, [sp, #24]             // b
    add     x6, sp, #28               // &delta_r
    add     x7, sp, #32               // &delta_g

    // Set up last parameter for calculate_delta_aarch64
    sub     sp, sp, #16               // Allocate space on stack for function call
    add     x8, sp, #48               // &delta_b (adjusted for current sp)
    str     x8, [sp]                  // Store delta_b ptr as parameter
    bl      calculate_delta_aarch64
    add     sp, sp, #16               // Clean up stack

    // Increment bufferIndex
    add     w26, w26, #3

    // Calculate xMask
    cmp     w25, w22                  // Compare x with widthMinus
    csel    w9, wzr, w27, ge          // xMask = 0 if x >= widthMinus, else all 1s

    // Apply masks and update buf1
    ldr     w10, [sp, #28]            // delta_r
    asr     w10, w10, #1              // delta_r >> 1
    and     w10, w10, w9              // (delta_r >> 1) & xMask
    str     w10, [x19, x26, lsl #2]   // buf1[bufferIndex] = result

    ldr     w10, [sp, #32]            // delta_g
    asr     w10, w10, #1              // delta_g >> 1
    and     w10, w10, w9              // (delta_g >> 1) & xMask
    str     w10, [x19, x26, lsl #2, #+4] // buf1[bufferIndex+1] = result

    ldr     w10, [sp, #36]            // delta_b
    asr     w10, w10, #1              // delta_b >> 1
    and     w10, w10, w9              // (delta_b >> 1) & xMask
    str     w10, [x19, x26, lsl #2, #+8] // buf1[bufferIndex+2] = result

    // Check if hasNextY
    cbz     w24, .skip_nexty_even

    // Calculate prevXMask
    cmp     w25, #0                   // Compare x with 0
    csel    w9, wzr, w27, le          // prevXMask = 0 if x <= 0, else all 1s

    // Update buf2 with delta values
    ldr     w10, [sp, #28]            // delta_r
    asr     w10, w10, #2              // delta_r >> 2
    and     w10, w10, w9              // (delta_r >> 2) & prevXMask
    str     w10, [x20, x26, lsl #2, #-24] // buf2[bufferIndex-6] = result

    ldr     w10, [sp, #32]            // delta_g
    asr     w10, w10, #2              // delta_g >> 2
    and     w10, w10, w9              // (delta_g >> 2) & prevXMask
    str     w10, [x20, x26, lsl #2, #-20] // buf2[bufferIndex-5] = result

    ldr     w10, [sp, #36]            // delta_b
    asr     w10, w10, #2              // delta_b >> 2
    and     w10, w10, w9              // (delta_b >> 2) & prevXMask
    str     w10, [x20, x26, lsl #2, #-16] // buf2[bufferIndex-4] = result

    // Update buf2 with delta values, no mask
    ldr     w10, [sp, #28]            // delta_r
    asr     w10, w10, #2              // delta_r >> 2
    str     w10, [x20, x26, lsl #2, #-12] // buf2[bufferIndex-3] = result

    ldr     w10, [sp, #32]            // delta_g
    asr     w10, w10, #2              // delta_g >> 2
    str     w10, [x20, x26, lsl #2, #-8] // buf2[bufferIndex-2] = result

    ldr     w10, [sp, #36]            // delta_b
    asr     w10, w10, #2              // delta_b >> 2
    str     w10, [x20, x26, lsl #2, #-4] // buf2[bufferIndex-1] = result

.skip_nexty_even:
    // Calculate mapColorIndex
    ldr     w9, [sp, #16]             // r
    lsr     w9, w9, #1                // r >> 1
    lsl     w9, w9, #14               // (r >> 1) << 14

    ldr     w10, [sp, #20]            // g
    lsr     w10, w10, #1              // g >> 1
    lsl     w10, w10, #7              // (g >> 1) << 7
    orr     w9, w9, w10               // | g component

    ldr     w10, [sp, #24]            // b
    lsr     w10, w10, #1              // b >> 1
    orr     w9, w9, w10               // | b component

    // Update the result array
    ldr     x10, [x29, #112]          // mapColorsPtr
    ldrb    w11, [x10, x9]            // mapColorsPtr[mapColorIndex]
    add     x12, x23, x25             // index = yIndex + x
    ldr     x13, [x29, #120]          // resultPtr
    strb    w11, [x13, x12]           // resultPtr[index] = result

    // Increment x and check loop condition
    add     w25, w25, #1              // x++
    cmp     w25, w21                  // Compare x with width
    blt     .loop_even

    // Clean up and return
    add     sp, sp, #48               // Free local variable space

    // Restore callee-saved registers
    ldp     x19, x20, [sp, #16]
    ldp     x21, x22, [sp, #32]
    ldp     x23, x24, [sp, #48]
    ldp     x25, x26, [sp, #64]
    ldp     x27, x28, [sp, #80]

    ldp     x29, x30, [sp], #128      // Restore frame pointer and link register
    ret

// void process_odd_row_aarch64(jint* bufferPtr, jint* colorsPtr, jbyte* mapColorsPtr, jbyte* resultPtr,
//                             int* buf1, int* buf2, jint width, jint widthMinus, jint yIndex, jboolean hasNextY)
// x0 = bufferPtr
// x1 = colorsPtr
// x2 = mapColorsPtr
// x3 = resultPtr
// x4 = buf1
// x5 = buf2
// x6 = width
// x7 = widthMinus
// [sp] = yIndex
// [sp+8] = hasNextY
process_odd_row_aarch64:
    // Allocate stack frame and save registers
    stp     x29, x30, [sp, #-128]!  // Save frame pointer and link register, allocate frame
    mov     x29, sp                  // Set frame pointer

    // Save callee-saved registers
    stp     x19, x20, [sp, #16]
    stp     x21, x22, [sp, #32]
    stp     x23, x24, [sp, #48]
    stp     x25, x26, [sp, #64]
    stp     x27, x28, [sp, #80]

    // Save parameters to callee-saved registers
    mov     x19, x4                  // buf1
    mov     x20, x5                  // buf2
    mov     x21, x6                  // width
    mov     x22, x7                  // widthMinus
    ldr     x23, [x29, #128]         // yIndex
    ldr     w24, [x29, #136]         // hasNextY

    // Store remaining parameters in frame
    str     x0, [x29, #96]           // bufferPtr
    str     x1, [x29, #104]          // colorsPtr
    str     x2, [x29, #112]          // mapColorsPtr
    str     x3, [x29, #120]          // resultPtr

    // Initialize w27 mask register to all ones
    mov     w27, #-1                 // all bits set

    // Initialize loop variables - starting from the end for odd rows
    sub     w25, w21, #1             // x = width - 1
    lsl     w26, w25, #1             // bufferIndex = x * 2
    add     w26, w26, w25            // bufferIndex = x * 3

    // Allocate space for local variables
    sub     sp, sp, #48              // Space for red, green, blue, colorIndex, r, g, b, delta_r, delta_g, delta_b

.loop_odd:
    // Calculate index and load rgb
    add     x9, x23, x25              // index = yIndex + x
    ldr     x0, [x29, #96]            // bufferPtr
    ldr     w0, [x0, x9, lsl #2]      // rgb = bufferPtr[index]

    // Process pixel - using odd version
    add     x1, x19, x26, lsl #2      // &buf1[bufferIndex]
    mov     x2, sp                    // &red
    add     x3, sp, #4                // &green
    add     x4, sp, #8                // &blue
    add     x5, sp, #12               // &colorIndex
    bl      process_pixel_aarch64_odd

    // Get colorIndex and load closest color
    ldr     w9, [sp, #12]             // colorIndex
    ldr     x0, [x29, #104]           // colorsPtr
    ldr     w10, [x0, x9, lsl #2]     // closest = colorsPtr[colorIndex]

    // Extract RGB components from closest color
    mov     w0, w10                   // closest
    add     x1, sp, #16               // &r
    add     x2, sp, #20               // &g
    add     x3, sp, #24               // &b
    bl      extract_rgb_aarch64

    // Calculate delta
    ldr     w0, [sp]                  // red
    ldr     w1, [sp, #4]              // green
    ldr     w2, [sp, #8]              // blue
    ldr     w3, [sp, #16]             // r
    ldr     w4, [sp, #20]             // g
    ldr     w5, [sp, #24]             // b
    add     x6, sp, #28               // &delta_r
    add     x7, sp, #32               // &delta_g

    // Set up last parameter for calculate_delta_aarch64
    sub     sp, sp, #16               // Allocate space on stack for function call
    add     x8, sp, #48               // &delta_b (adjusted for current sp)
    str     x8, [sp]                  // Store delta_b ptr as parameter
    bl      calculate_delta_aarch64
    add     sp, sp, #16               // Clean up stack

    // Increment bufferIndex (decreasing for odd rows)
    sub     w26, w26, #3

    // Calculate xMask (reversed for odd rows)
    cmp     w25, #0                   // Compare x with 0
    csel    w9, wzr, w27, le          // xMask = 0 if x <= 0, else all 1s

    // Apply masks and update buf1
    ldr     w10, [sp, #28]            // delta_r
    asr     w10, w10, #1              // delta_r >> 1
    and     w10, w10, w9              // (delta_r >> 1) & xMask
    str     w10, [x19, x26, lsl #2]   // buf1[bufferIndex] = result

    ldr     w10, [sp, #32]            // delta_g
    asr     w10, w10, #1              // delta_g >> 1
    and     w10, w10, w9              // (delta_g >> 1) & xMask
    str     w10, [x19, x26, lsl #2, #+4] // buf1[bufferIndex+1] = result

    ldr     w10, [sp, #36]            // delta_b
    asr     w10, w10, #1              // delta_b >> 1
    and     w10, w10, w9              // (delta_b >> 1) & xMask
    str     w10, [x19, x26, lsl #2, #+8] // buf1[bufferIndex+2] = result

    // Check if hasNextY
    cbz     w24, .skip_nexty_odd

    // Calculate prevXMask (reversed logic for odd rows)
    cmp     w25, w22                  // Compare x with widthMinus
    csel    w9, wzr, w27, ge          // prevXMask = 0 if x >= widthMinus, else all 1s

    // Update buf2 with delta values
    ldr     w10, [sp, #28]            // delta_r
    asr     w10, w10, #2              // delta_r >> 2
    and     w10, w10, w9              // (delta_r >> 2) & prevXMask
    str     w10, [x20, x26, lsl #2, #+24] // buf2[bufferIndex+6] = result

    ldr     w10, [sp, #32]            // delta_g
    asr     w10, w10, #2              // delta_g >> 2
    and     w10, w10, w9              // (delta_g >> 2) & prevXMask
    str     w10, [x20, x26, lsl #2, #+20] // buf2[bufferIndex+5] = result

    ldr     w10, [sp, #36]            // delta_b
    asr     w10, w10, #2              // delta_b >> 2
    and     w10, w10, w9              // (delta_b >> 2) & prevXMask
    str     w10, [x20, x26, lsl #2, #+16] // buf2[bufferIndex+4] = result

    // Update buf2 with delta values, no mask
    ldr     w10, [sp, #28]            // delta_r
    asr     w10, w10, #2              // delta_r >> 2
    str     w10, [x20, x26, lsl #2, #+12] // buf2[bufferIndex+3] = result

    ldr     w10, [sp, #32]            // delta_g
    asr     w10, w10, #2              // delta_g >> 2
    str     w10, [x20, x26, lsl #2, #+8] // buf2[bufferIndex+2] = result

    ldr     w10, [sp, #36]            // delta_b
    asr     w10, w10, #2              // delta_b >> 2
    str     w10, [x20, x26, lsl #2, #+4] // buf2[bufferIndex+1] = result

.skip_nexty_odd:
    // Calculate mapColorIndex
    ldr     w9, [sp, #16]             // r
    lsr     w9, w9, #1                // r >> 1
    lsl     w9, w9, #14               // (r >> 1) << 14

    ldr     w10, [sp, #20]            // g
    lsr     w10, w10, #1              // g >> 1
    lsl     w10, w10, #7              // (g >> 1) << 7
    orr     w9, w9, w10               // | g component

    ldr     w10, [sp, #24]            // b
    lsr     w10, w10, #1              // b >> 1
    orr     w9, w9, w10               // | b component

    // Update the result array
    ldr     x10, [x29, #112]          // mapColorsPtr
    ldrb    w11, [x10, x9]            // mapColorsPtr[mapColorIndex]
    add     x12, x23, x25             // index = yIndex + x
    ldr     x13, [x29, #120]          // resultPtr
    strb    w11, [x13, x12]           // resultPtr[index] = result

    // Decrement x and check loop condition
    sub     w25, w25, #1              // x--
    cmp     w25, #0                   // Compare x with 0
    bge     .loop_odd                 // Continue if x >= 0

    // Clean up and return
    add     sp, sp, #48               // Free local variable space

    // Restore callee-saved registers
    ldp     x19, x20, [sp, #16]
    ldp     x21, x22, [sp, #32]
    ldp     x23, x24, [sp, #48]
    ldp     x25, x26, [sp, #64]
    ldp     x27, x28, [sp, #80]

    ldp     x29, x30, [sp], #128      // Restore frame pointer and link register
    ret
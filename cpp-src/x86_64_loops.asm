; x86_64_loops.asm
section .text
global process_even_row_x86_64
global process_odd_row_x86_64
extern process_pixel_x86_64_even
extern process_pixel_x86_64_odd
extern extract_rgb_x86_64
extern calculate_delta_x86_64

; void process_even_row_x86_64(jint* bufferPtr, jint* colorsPtr, jbyte* mapColorsPtr, jbyte* resultPtr,
;                             int* buf1, int* buf2, jint width, jint widthMinus, jint yIndex, jboolean hasNextY)
; Arguments:
;   rdi = bufferPtr
;   rsi = colorsPtr
;   rdx = mapColorsPtr
;   rcx = resultPtr
;   r8 = buf1
;   r9 = buf2
;   [rsp+8] = width
;   [rsp+16] = widthMinus
;   [rsp+24] = yIndex
;   [rsp+32] = hasNextY
process_even_row_x86_64:
    push    rbp
    mov     rbp, rsp
    push    rbx
    push    r12
    push    r13
    push    r14
    push    r15

    ; Allocate all stack space at once (including space for function calls)
    sub     rsp, 128              ; Allocate sufficient stack space for all variables and function calls

    ; Save parameters at fixed offsets from rbp
    mov     [rbp-48], rdi         ; bufferPtr
    mov     [rbp-56], rsi         ; colorsPtr
    mov     [rbp-64], rdx         ; mapColorsPtr
    mov     [rbp-72], rcx         ; resultPtr
    mov     [rbp-80], r8          ; buf1
    mov     [rbp-88], r9          ; buf2
    mov     r10, [rbp+16]
    mov     [rbp-96], r10         ; width
    mov     r10, [rbp+24]
    mov     [rbp-104], r10        ; widthMinus
    mov     r10, [rbp+32]
    mov     [rbp-112], r10        ; yIndex
    mov     r10, [rbp+40]
    mov     [rbp-120], r10        ; hasNextY

    ; Initialize loop variables
    xor     r12, r12             ; x = 0
    xor     r13, r13             ; bufferIndex = 0

    ; Get parameters into registers for the loop
    mov     rbx, [rbp-48]        ; bufferPtr
    mov     r14, [rbp-56]        ; colorsPtr
    mov     r15, [rbp-72]        ; resultPtr

    ; Start the loop
.loop:
    mov     rax, [rbp-112]       ; yIndex
    add     rax, r12             ; index = yIndex + x
    mov     r10d, [rbx + rax*4]  ; rgb = bufferPtr[index]

    ; Process pixel - allocate space for local variables in stack space we already reserved
    mov     rdi, r10d            ; rgb
    mov     r8, [rbp-80]         ; buf1
    lea     rsi, [r8 + r13*4]    ; &buf1[bufferIndex]
    lea     rdx, [rbp-128]       ; &red
    lea     rcx, [rbp-132]       ; &green
    mov     r8, rbp              ; Use a register temporarily
    lea     r8, [rbp-136]        ; &blue
    lea     r9, [rbp-140]        ; &colorIndex
    call    process_pixel_x86_64_even

    ; Get colorIndex and load closest color
    mov     eax, [rbp-140]       ; colorIndex
    mov     r14, [rbp-56]        ; refresh colorsPtr
    mov     r11d, [r14 + rax*4]  ; closest = colorsPtr[colorIndex]

    ; Extract RGB components from closest color
    mov     rdi, r11d            ; closest
    lea     rsi, [rbp-144]       ; &r
    lea     rdx, [rbp-148]       ; &g
    lea     rcx, [rbp-152]       ; &b
    call    extract_rgb_x86_64

    ; Calculate delta - use fixed offsets from rbp for all parameters
    mov     edi, [rbp-128]       ; red
    mov     esi, [rbp-132]       ; green
    mov     edx, [rbp-136]       ; blue
    mov     ecx, [rbp-144]       ; r
    mov     r8d, [rbp-148]       ; g
    mov     r9d, [rbp-152]       ; b

    ; Set up remaining parameters on stack - properly aligned
    lea     rax, [rbp-156]       ; &delta_r
    mov     [rsp], rax
    lea     rax, [rbp-160]       ; &delta_g
    mov     [rsp+8], rax
    lea     rax, [rbp-164]       ; &delta_b
    mov     [rsp+16], rax
    call    calculate_delta_x86_64

    ; Increment bufferIndex
    add     r13, 3

    ; Calculate xMask
    mov     eax, 0               ; Default mask = 0
    cmp     r12, [rbp-104]       ; Compare x with widthMinus
    jge     .after_mask
    mov     eax, -1              ; Mask = -1 if x < widthMinus
.after_mask:

    ; Apply masks and update buf1
    mov     r8, [rbp-80]         ; refresh buf1
    mov     r10d, [rbp-156]      ; delta_r
    sar     r10d, 1              ; delta_r >> 1
    and     r10d, eax            ; (delta_r >> 1) & xMask
    mov     [r8 + r13*4], r10d   ; buf1[bufferIndex] = result

    mov     r10d, [rbp-160]      ; delta_g
    sar     r10d, 1              ; delta_g >> 1
    and     r10d, eax            ; (delta_g >> 1) & xMask
    mov     [r8 + r13*4 + 4], r10d ; buf1[bufferIndex+1] = result

    mov     r10d, [rbp-164]      ; delta_b
    sar     r10d, 1              ; delta_b >> 1
    and     r10d, eax            ; (delta_b >> 1) & xMask
    mov     [r8 + r13*4 + 8], r10d ; buf1[bufferIndex+2] = result

    ; Check if hasNextY
    cmp     DWORD [rbp-120], 0   ; hasNextY
    je      .skip_nexty

    ; Calculate prevXMask
    mov     eax, 0               ; Default mask = 0
    test    r12, r12             ; Compare x with 0
    jle     .after_prev_mask
    mov     eax, -1              ; Mask = -1 if x > 0
.after_prev_mask:

    ; Update buf2 with delta values
    mov     r9, [rbp-88]         ; refresh buf2
    mov     r10d, [rbp-156]      ; delta_r
    sar     r10d, 2              ; delta_r >> 2
    and     r10d, eax            ; (delta_r >> 2) & prevXMask
    mov     [r9 + r13*4 - 24], r10d ; buf2[bufferIndex-6] = result

    mov     r10d, [rbp-160]      ; delta_g
    sar     r10d, 2              ; delta_g >> 2
    and     r10d, eax            ; (delta_g >> 2) & prevXMask
    mov     [r9 + r13*4 - 20], r10d ; buf2[bufferIndex-5] = result

    mov     r10d, [rbp-164]      ; delta_b
    sar     r10d, 2              ; delta_b >> 2
    and     r10d, eax            ; (delta_b >> 2) & prevXMask
    mov     [r9 + r13*4 - 16], r10d ; buf2[bufferIndex-4] = result

    ; Update buf2 with delta values, no mask
    mov     r10d, [rbp-156]      ; delta_r
    sar     r10d, 2              ; delta_r >> 2
    mov     [r9 + r13*4 - 12], r10d ; buf2[bufferIndex-3] = result

    mov     r10d, [rbp-160]      ; delta_g
    sar     r10d, 2              ; delta_g >> 2
    mov     [r9 + r13*4 - 8], r10d ; buf2[bufferIndex-2] = result

    mov     r10d, [rbp-164]      ; delta_b
    sar     r10d, 2              ; delta_b >> 2
    mov     [r9 + r13*4 - 4], r10d ; buf2[bufferIndex-1] = result

.skip_nexty:
    ; Calculate mapColorIndex
    mov     eax, [rbp-144]       ; r
    shr     eax, 1               ; r >> 1
    shl     eax, 14              ; (r >> 1) << 14

    mov     r10d, [rbp-148]      ; g
    shr     r10d, 1              ; g >> 1
    shl     r10d, 7              ; (g >> 1) << 7
    or      eax, r10d            ; | g component

    mov     r10d, [rbp-152]      ; b
    shr     r10d, 1              ; b >> 1
    or      eax, r10d            ; | b component

    ; Update the result array
    mov     r10, [rbp-64]        ; mapColorsPtr
    mov     r11, [rbp-112]       ; yIndex
    add     r11, r12             ; index = yIndex + x
    mov     bl, [r10 + rax]      ; mapColorsPtr[mapColorIndex]
    mov     r10, [rbp-72]        ; resultPtr
    mov     [r10 + r11], bl      ; resultPtr[index] = result

    ; Increment x and check loop condition
    inc     r12                  ; x++
    cmp     r12, [rbp-96]        ; Compare x with width
    jl      .loop

    ; Clean up and return
    add     rsp, 128             ; Restore stack pointer

    ; Restore registers and return
    pop     r15
    pop     r14
    pop     r13
    pop     r12
    pop     rbx
    pop     rbp
    ret

process_odd_row_x86_64:
    push    rbp
    mov     rbp, rsp
    push    rbx
    push    r12
    push    r13
    push    r14
    push    r15

    ; Allocate all stack space at once
    sub     rsp, 128              ; Allocate sufficient stack space

    ; Save parameters at fixed offsets from rbp
    mov     [rbp-48], rdi         ; bufferPtr
    mov     [rbp-56], rsi         ; colorsPtr
    mov     [rbp-64], rdx         ; mapColorsPtr
    mov     [rbp-72], rcx         ; resultPtr
    mov     [rbp-80], r8          ; buf1
    mov     [rbp-88], r9          ; buf2
    mov     r10, [rbp+16]
    mov     [rbp-96], r10         ; width
    mov     r10, [rbp+24]
    mov     [rbp-104], r10        ; widthMinus
    mov     r10, [rbp+32]
    mov     [rbp-112], r10        ; yIndex
    mov     r10, [rbp+40]
    mov     [rbp-120], r10        ; hasNextY

    ; Initialize loop variables (reversed for odd rows)
    mov     r12, [rbp-96]        ; x = width
    dec     r12                  ; x = width - 1
    lea     r13, [r12*2+r12]     ; bufferIndex = x * 3

    ; Get parameters into registers for the loop
    mov     rbx, [rbp-48]        ; bufferPtr
    mov     r14, [rbp-56]        ; colorsPtr
    mov     r15, [rbp-72]        ; resultPtr

    ; Start the loop
.loop:
    mov     rax, [rbp-112]       ; yIndex
    add     rax, r12             ; index = yIndex + x
    mov     r10d, [rbx + rax*4]  ; rgb = bufferPtr[index]

    ; Process pixel - using odd version
    mov     rdi, r10d            ; rgb
    mov     r8, [rbp-80]         ; refresh buf1
    lea     rsi, [r8 + r13*4]    ; &buf1[bufferIndex]
    lea     rdx, [rbp-128]       ; &red
    lea     rcx, [rbp-132]       ; &green
    mov     r8, rbp              ; Use a register temporarily
    lea     r8, [rbp-136]        ; &blue
    lea     r9, [rbp-140]        ; &colorIndex
    call    process_pixel_x86_64_odd

    ; Get colorIndex and load closest color
    mov     eax, [rbp-140]       ; colorIndex
    mov     r14, [rbp-56]        ; refresh colorsPtr
    mov     r11d, [r14 + rax*4]  ; closest = colorsPtr[colorIndex]

    ; Extract RGB components from closest color
    mov     rdi, r11d            ; closest
    lea     rsi, [rbp-144]       ; &r
    lea     rdx, [rbp-148]       ; &g
    lea     rcx, [rbp-152]       ; &b
    call    extract_rgb_x86_64

    ; Calculate delta - use fixed offsets from rbp
    mov     edi, [rbp-128]       ; red
    mov     esi, [rbp-132]       ; green
    mov     edx, [rbp-136]       ; blue
    mov     ecx, [rbp-144]       ; r
    mov     r8d, [rbp-148]       ; g
    mov     r9d, [rbp-152]       ; b

    ; Set up remaining parameters on stack
    lea     rax, [rbp-156]       ; &delta_r
    mov     [rsp], rax
    lea     rax, [rbp-160]       ; &delta_g
    mov     [rsp+8], rax
    lea     rax, [rbp-164]       ; &delta_b
    mov     [rsp+16], rax
    call    calculate_delta_x86_64

    ; Decrement bufferIndex (for odd rows - moving backward)
    sub     r13, 3

    ; Calculate xMask (reversed for odd rows)
    mov     eax, 0               ; Default mask = 0
    test    r12, r12             ; Compare x with 0
    jle     .after_mask
    mov     eax, -1              ; Mask = -1 if x > 0
.after_mask:

    ; Apply masks and update buf1
    mov     r8, [rbp-80]         ; refresh buf1
    mov     r10d, [rbp-156]      ; delta_r
    sar     r10d, 1              ; delta_r >> 1
    and     r10d, eax            ; (delta_r >> 1) & xMask
    mov     [r8 + r13*4], r10d   ; buf1[bufferIndex] = result

    mov     r10d, [rbp-160]      ; delta_g
    sar     r10d, 1              ; delta_g >> 1
    and     r10d, eax            ; (delta_g >> 1) & xMask
    mov     [r8 + r13*4 + 4], r10d ; buf1[bufferIndex+1] = result

    mov     r10d, [rbp-164]      ; delta_b
    sar     r10d, 1              ; delta_b >> 1
    and     r10d, eax            ; (delta_b >> 1) & xMask
    mov     [r8 + r13*4 + 8], r10d ; buf1[bufferIndex+2] = result

    ; Check if hasNextY
    cmp     DWORD [rbp-120], 0   ; hasNextY
    je      .skip_nexty

    ; Calculate nextXMask
    mov     eax, 0               ; Default mask = 0
    cmp     r12, [rbp-104]       ; Compare x with widthMinus
    jge     .after_next_mask
    mov     eax, -1              ; Mask = -1 if x < widthMinus
.after_next_mask:

    ; Update buf2 with delta values
    mov     r9, [rbp-88]         ; refresh buf2
    mov     r10d, [rbp-156]      ; delta_r
    sar     r10d, 2              ; delta_r >> 2
    and     r10d, eax            ; (delta_r >> 2) & nextXMask
    mov     [r9 + r13*4 + 12], r10d ; buf2[bufferIndex+3] = result

    mov     r10d, [rbp-160]      ; delta_g
    sar     r10d, 2              ; delta_g >> 2
    and     r10d, eax            ; (delta_g >> 2) & nextXMask
    mov     [r9 + r13*4 + 16], r10d ; buf2[bufferIndex+4] = result

    mov     r10d, [rbp-164]      ; delta_b
    sar     r10d, 2              ; delta_b >> 2
    and     r10d, eax            ; (delta_b >> 2) & nextXMask
    mov     [r9 + r13*4 + 20], r10d ; buf2[bufferIndex+5] = result

    ; Update buf2 with delta values, no mask
    mov     r10d, [rbp-156]      ; delta_r
    sar     r10d, 2              ; delta_r >> 2
    mov     [r9 + r13*4], r10d   ; buf2[bufferIndex] = result

    mov     r10d, [rbp-160]      ; delta_g
    sar     r10d, 2              ; delta_g >> 2
    mov     [r9 + r13*4 + 4], r10d ; buf2[bufferIndex+1] = result

    mov     r10d, [rbp-164]      ; delta_b
    sar     r10d, 2              ; delta_b >> 2
    mov     [r9 + r13*4 + 8], r10d ; buf2[bufferIndex+2] = result

.skip_nexty:
    ; Calculate mapColorIndex
    mov     eax, [rbp-144]       ; r
    shr     eax, 1               ; r >> 1
    shl     eax, 14              ; (r >> 1) << 14

    mov     r10d, [rbp-148]      ; g
    shr     r10d, 1              ; g >> 1
    shl     r10d, 7              ; (g >> 1) << 7
    or      eax, r10d            ; | g component

    mov     r10d, [rbp-152]      ; b
    shr     r10d, 1              ; b >> 1
    or      eax, r10d            ; | b component

    ; Update the result array
    mov     r10, [rbp-64]        ; mapColorsPtr
    mov     r11, [rbp-112]       ; yIndex
    add     r11, r12             ; index = yIndex + x
    mov     bl, [r10 + rax]      ; mapColorsPtr[mapColorIndex]
    mov     r10, [rbp-72]        ; resultPtr
    mov     [r10 + r11], bl      ; resultPtr[index] = result

    ; Decrement x and check loop condition (odd rows go in reverse)
    dec     r12                  ; x--
    jns     .loop                ; Continue if x >= 0

    ; Clean up and return
    add     rsp, 128             ; Restore stack pointer

    ; Restore registers and return
    pop     r15
    pop     r14
    pop     r13
    pop     r12
    pop     rbx
    pop     rbp
    ret
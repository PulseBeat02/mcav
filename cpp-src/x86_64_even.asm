; x86_64_even.asm
section .text
global process_pixel_x86_64_even
global extract_rgb_x86_64
global calculate_delta_x86_64

; void process_pixel_x86_64_even(int rgb, int* buf1, int* red, int* green, int* blue, int* colorIndex)
; Arguments:
;   rdi = rgb
;   rsi = buf1 pointer
;   rdx = red pointer
;   rcx = green pointer
;   r8 = blue pointer
;   r9 = colorIndex pointer
process_pixel_x86_64_even:
    ; Extract RGB components
    mov     eax, edi        ; eax = rgb
    mov     ebx, eax
    mov     r10d, eax
    shr     eax, 16         ; red = (rgb >> 16)
    and     eax, 0xFF       ; red &= 0xFF
    shr     ebx, 8          ; green = (rgb >> 8)
    and     ebx, 0xFF       ; green &= 0xFF
    and     r10d, 0xFF      ; blue = rgb & 0xFF

    ; Apply dither buffer adjustments
    add     eax, [rsi]      ; red += buf1[0]
    mov     r11d, eax
    neg     r11d
    sar     r11d, 31
    and     eax, r11d       ; Clamp lower bound to 0
    mov     r11d, eax
    sub     r11d, 256
    sar     r11d, 31
    not     r11d
    and     eax, r11d       ; Clamp upper bound to 255

    add     ebx, [rsi+4]    ; green += buf1[1]
    mov     r11d, ebx
    neg     r11d
    sar     r11d, 31
    and     ebx, r11d       ; Clamp lower bound to 0
    mov     r11d, ebx
    sub     r11d, 256
    sar     r11d, 31
    not     r11d
    and     ebx, r11d       ; Clamp upper bound to 255

    add     r10d, [rsi+8]   ; blue += buf1[2]
    mov     r11d, r10d
    neg     r11d
    sar     r11d, 31
    and     r10d, r11d      ; Clamp lower bound to 0
    mov     r11d, r10d
    sub     r11d, 256
    sar     r11d, 31
    not     r11d
    and     r10d, r11d      ; Clamp upper bound to 255

    ; Calculate color index
    mov     r11d, eax       ; r11d = red
    shr     r11d, 1         ; red >> 1
    shl     r11d, 14        ; (red >> 1) << 14

    mov     r12d, ebx       ; r12d = green
    shr     r12d, 1         ; green >> 1
    shl     r12d, 7         ; (green >> 1) << 7
    or      r11d, r12d      ; | green component

    mov     r12d, r10d      ; r12d = blue
    shr     r12d, 1         ; blue >> 1
    or      r11d, r12d      ; | blue component

    ; Store results
    mov     [rdx], eax      ; *red = red
    mov     [rcx], ebx      ; *green = green
    mov     [r8], r10d      ; *blue = blue
    mov     [r9], r11d      ; *colorIndex = colorIndex

    ret

; void extract_rgb_x86_64(int closest, int* r, int* g, int* b)
; Arguments:
;   rdi = closest
;   rsi = r pointer
;   rdx = g pointer
;   rcx = b pointer
extract_rgb_x86_64:
    mov     eax, edi        ; eax = closest
    shr     eax, 16
    and     eax, 0xFF
    mov     [rsi], eax      ; *r = (closest >> 16) & 0xFF

    mov     eax, edi        ; eax = closest
    shr     eax, 8
    and     eax, 0xFF
    mov     [rdx], eax      ; *g = (closest >> 8) & 0xFF

    mov     eax, edi        ; eax = closest
    and     eax, 0xFF
    mov     [rcx], eax      ; *b = closest & 0xFF

    ret

; void calculate_delta_x86_64(int red, int green, int blue, int r, int g, int b,
;                             int* delta_r, int* delta_g, int* delta_b)
; Arguments:
;   rdi = red
;   rsi = green
;   rdx = blue
;   rcx = r
;   r8 = g
;   r9 = b
;   [rsp+8] = delta_r pointer
;   [rsp+16] = delta_g pointer
;   [rsp+24] = delta_b pointer
calculate_delta_x86_64:
    mov     eax, edi        ; eax = red
    sub     eax, ecx        ; eax = red - r
    mov     r10, [rsp+8]    ; r10 = delta_r pointer
    mov     [r10], eax      ; *delta_r = red - r

    mov     eax, esi        ; eax = green
    sub     eax, r8d        ; eax = green - g
    mov     r10, [rsp+16]   ; r10 = delta_g pointer
    mov     [r10], eax      ; *delta_g = green - g

    mov     eax, edx        ; eax = blue
    sub     eax, r9d        ; eax = blue - b
    mov     r10, [rsp+24]   ; r10 = delta_b pointer
    mov     [r10], eax      ; *delta_b = blue - b

    ret
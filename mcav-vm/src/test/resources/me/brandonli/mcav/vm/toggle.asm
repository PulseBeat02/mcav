; Keep the first character changing: QEMU slows idle VNC refreshes, delaying picture without delaying sound.
bits 16
org 0x7c00
start:
    xor ax, ax
    mov ds, ax
    mov ss, ax
    mov sp, 0x7c00
    mov ax, 0xb800
    mov es, ax
    sti
    mov al, 0xb6            ; PIT channel 2, lobyte/hibyte, square wave
    out 0x43, al
    mov ax, 1193
    out 0x42, al
    mov al, ah
    out 0x42, al
    xor si, si
.next:
    mov bl, [waits + si]
    inc si
    and si, 7
    mov dx, [0x046c]        ; BIOS tick count
.wait:
    inc byte [es:0]         ; the first character keeps changing, so QEMU keeps refreshing its display
    mov ax, [0x046c]
    sub ax, dx
    cmp al, bl
    jb .wait
    in al, 0x61
    xor al, 0x03            ; switch the speaker gate and data together
    out 0x61, al
    test al, 0x03
    mov ax, 0x0020
    jz .fill
    mov ax, 0x4420          ; space on red
.fill:
    xor di, di
    mov cx, 80 * 25
    rep stosw
    jmp .next
waits:
    db 5, 3, 8, 4, 9, 6, 3, 7
times 510 - ($ - $$) db 0
dw 0xaa55

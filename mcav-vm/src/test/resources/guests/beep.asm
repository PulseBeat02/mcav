; 512-byte boot sector: PIT channel 2 square wave at 1193182/1193 = 1000.15 Hz into the PC speaker, then halt.
bits 16
org 0x7c00
start:
    cli
    mov al, 0xb6          ; channel 2, lobyte/hibyte, mode 3 (square wave), binary
    out 0x43, al
    mov ax, 1193          ; divisor
    out 0x42, al
    mov al, ah
    out 0x42, al
    in al, 0x61
    or al, 0x03           ; gate channel 2 + speaker data enable
    out 0x61, al
.hang:
    hlt
    jmp .hang
times 510 - ($ - $$) db 0
dw 0xaa55

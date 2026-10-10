; This file is part of mcav, a media playback library for Java
; Copyright (C) Brandon Li <https://brandonli.me/>
;
; This program is free software: you can redistribute it and/or modify
; it under the terms of the GNU General Public License as published by
; the Free Software Foundation, either version 3 of the License, or
; (at your option) any later version.
;
; This program is distributed in the hope that it will be useful,
; but WITHOUT ANY WARRANTY; without even the implied warranty of
; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
; GNU General Public License for more details.
;
; You should have received a copy of the GNU General Public License
; along with this program.  If not, see <https://www.gnu.org/licenses/>.

; PIT divisor 1193 gives 1193182/1193 = 1000.15 Hz, matching the test tone.
bits 16
org 0x7c00
start:
    cli
    mov al, 0xb6          ; channel 2, lobyte/hibyte, mode 3 (square wave), binary
    out 0x43, al
    mov ax, 1193
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

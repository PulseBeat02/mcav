# Virtual Machines

`/mcav vm create` boots a QEMU virtual machine and shows its display on a wall of maps, with its sound in an audio
output. Players click it by clicking the wall and type into it through chat, like a [browser](browser.md).

```{warning}
QEMU 6.0 or newer must be installed on the server and on the `PATH` of the server's process; MCAV never installs it.
Install it from your package manager or from the [QEMU website](https://www.qemu.org/download/). Without it the
command says so.
```

```text
/mcav screen 10x6 0 BLACK_CONCRETE ~ ~ ~
/mcav vm create @a 1280x720 30 10x6 0 NEAREST_COLOR X86_64 HTTP_SERVER -m 2048M -smp 2 -cdrom "alpine linux.iso"
```

The arguments are the viewers, the size the display is scaled to, the frame rate asked of QEMU (1 to 240), the wall's
size and first map id, the dithering algorithm, the CPU architecture, the audio output, and QEMU's options, which take
the rest of the line. A `--codec mcv2` at the very end is not passed to QEMU: it chooses the
[codec of the wall](commands.md#the-codec-of-a-wall-of-maps). One machine runs at a time; `/mcav vm release` powers it
off. MCAV picks the fastest accelerator of the machine (KVM, WHPX or HVF) and falls back to software emulation.

## Disk Images and Options

QEMU can read and write any file of the server, load plugin libraries, share folders and publish its display, so the
plugin accepts only the options that describe a machine ([the full list](commands.md#qemu-options)):

- **Disk images** with `-cdrom`, `-drive file=...`, `-hda` to `-hdd` and `-fda`, `-fdb`, and only files of the plugin's
  `plugins/MCAV/iso` folder, named without their folder. Put your images there, or link them in; nothing else on the
  server can be booted.
- **Hardware** with `-m`, `-smp`, `-cpu`, `-machine`, `-accel`, `-boot`, `-name`, `-k`, `-vga` and `-rtc`, each with the
  values of its kind only, and a few switches such as `-snapshot` and `-no-reboot`.
- **Memory**: at most half of the server's memory (or of its container's), and at least 512 MiB.

The display always stays on the loopback address the plugin chose for it. The guest's network card reaches nothing,
neither the internet nor the server, unless `vm.allow-network` is on in `config.yml`.

## Sound

MCAV gives an **x86-64** machine of the PC or Q35 family (the default machine, `pc`, `q35`) a sound card itself, an
Intel HD Audio card and the PC speaker, so the guest needs no option for it, only a driver, which every current
operating system has. Its sound plays through the chosen audio output like a video's, and takes the outputs over from
a playing video or browser. It is held about 70 ms on purpose, so that it plays with QEMU's picture, which refreshes 30
ms after a change at the earliest. Machines of other architectures have no sound and must choose `NONE`. A
configuration that sets `-audio`, `-audiodev`, `-vnc` or routes the PC speaker itself is refused: the plugin owns the
sound and the display.

## Clicking and Typing

Players with `mcav.vm.interact` click the machine by clicking the wall; `/mcav vm interact` types their chat into it,
and a key name such as `Return` or `Escape` presses that key. The permission is what allows the clicks, not only the
chat mode.

## Known Limits

With `vm.allow-network` on, the guest's user-mode network can reach the server's loopback address (`10.0.2.2` inside
the guest). QEMU's VNC display stays on the loopback address and asks for a random password only the plugin knows, so
another process on the server cannot watch it. QEMU is a child process of the server: if the server is killed hard, without releasing its
players, a running machine keeps running.

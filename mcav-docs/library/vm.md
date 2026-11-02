# Virtualization Module

```{warning}
On x86-64 and ARM64 Linux, MCAV installs QEMU from Debian packages when it is missing. Elsewhere you must install QEMU
6.0 or newer yourself to use the virtual machine player: install it from your package manager or follow the steps
[here](https://www.qemu.org/download/), and make sure the `qemu-system-*` programs are on the `PATH`.
```

One of the most unique features of MCAV is streaming virtual machines. Add the `mcav-vm` module together with the
`mcav-vnc` module it is built on, and install both modules when you create the library instance. `mcav-vm` does not
bring `mcav-vnc` with it: without its own line the code compiles, then fails when `VncModule` is loaded.

```kotlin
dependencies {
    implementation("me.brandonli:mcav-vnc:1.0.0-SNAPSHOT")
    implementation("me.brandonli:mcav-vm:1.0.0-SNAPSHOT")
}
```

```java
  final MCAVApi api = MCAV.api();
  api.install(VncModule.class, VmModule.class);

  final VmModule vmModule = api.getModule(VmModule.class);
  final boolean qemuAvailable = vmModule.isQemuAvailable();
  if (!qemuAvailable) {
    LOGGER.warn("QEMU is missing and MCAV cannot install it on this platform, so virtual machines cannot start.");
  }
```

The QEMU command line is an immutable `VmConfiguration`, built with the `VmConfigurationBuilder` that
`VmConfiguration.builder()` returns, and a `VmSettings` describes how the machine is streamed: the size frames are
scaled to and the frame rate requested from QEMU. The player adds the display, VNC, and pointer options itself, and
picks the fastest accelerator of the machine (KVM, WHPX, or HVF), falling back to software emulation when the
accelerator is unavailable. The VNC display listens on the loopback address and asks for a random password the player
alone knows; QEMU reads it from a file of a folder only your user can open, which is deleted once QEMU starts.

The example boots an ISO image and presses a key in its boot menu. The `display` filter is yours and shows the frames;
release the returned player to stop the machine. QEMU is a process of its own: a JVM that ends without releasing the
player, or is killed, leaves it running, with its memory and its VNC port. Every QEMU that MCAV starts is recorded in
`~/.mcav/vm`, and the next start of the VM module (`VmModule.start`, part of `MCAVApi.install`) stops each recorded
QEMU whose JVM is gone and logs it; a QEMU of another JVM that still runs is left alone. Releasing the library
(`MCAVApi.release`) does not stop the machines either; release every player first.

```java
  public VmPlayer bootIsoImage(final Path isoFile, final VideoFilter display) {
    final VideoPipelineStep pipeline = VideoPipelineStep.of(display);

    final String isoPath = isoFile.toString();
    final int memoryMegabytes = 2048;
    final int cores = 2;
    final VmConfigurationBuilder builder = VmConfiguration.builder();
    final VmConfiguration configuration = builder.cdrom(isoPath).memory(memoryMegabytes).cores(cores).build();

    final int width = 1024;
    final int height = 768;
    final int framesPerSecond = 30;
    final VmSettings settings = VmSettings.of(width, height, framesPerSecond);
    final VmPlayer player = VmPlayer.create();
    final VideoAttachableCallback videoCallback = player.getVideoAttachableCallback();
    videoCallback.attach(pipeline);

    player.start(settings, VmPlayer.Architecture.X86_64, configuration);

    final int clickX = 512;
    final int clickY = 384;
    final String returnKey = "Return";
    player.sendMouseEvent(MouseClick.LEFT, clickX, clickY);
    player.sendKeyEvent(returnKey);

    return player;
  }
```

A machine whose configuration chooses no network (`-nic`, `-netdev`, `-net` or `-nodefaults`) gets
`-nic user,restrict=on`: a network card that reaches neither the host nor the internet, apart from the forwards you
add. QEMU's own default network, which `network("user")` gives back, reaches the internet, and in it the address
10.0.2.2 is the machine QEMU runs on: the guest reaches every service there that listens only on the loopback address.
`network("none")` gives the guest no network card at all.

Add options that QEMU accepts more than once, such as `-drive` or `-device`, with `drive(...)`,
`device(...)`, or `repeatable(key, value)`; every other option replaces its earlier value. `start` throws an
`ExecutableNotInPathException` when the QEMU program of the architecture is missing and MCAV cannot install it on the
platform, and a `VmException` with the output of QEMU when the machine fails to start. When the installation of QEMU
fails, `start` throws the `InstallationException`, `DownloadException` or `FileOperationException` of `mcav-common`
instead; catch `MCAVException` to handle both the virtual machine and the installation failures. A configuration that
sets an option the player reserves is refused with an `IllegalArgumentException`.

Behind the scenes, the player starts QEMU with a VNC display bound to the local machine and connects the `mcav-vnc`
player to it. You are responsible for giving QEMU a valid configuration; MCAV reports QEMU's errors but does not
try to fix them.

## Sound

The player hands the sound of the guest to its audio pipeline, in the format every audio filter of MCAV takes: 16-bit
little-endian stereo PCM at 48 kHz. Attach a pipeline like the video one:

```java
    final AudioPipelineStep audioPipeline = AudioPipelineStep.of(speakers);
    final AudioAttachableCallback audioCallback = player.getAudioAttachableCallback();
    audioCallback.attach(audioPipeline);
```

Only x86-64 machines of the PC and Q35 families (the default machine, `pc`, `q35` and their versions) have sound.
The player gives them an Intel HD Audio card (ICH9 on Q35) and routes the PC speaker, both into an audio backend that
plays on no device of the host; QEMU's VNC server hands their sound to a second VNC connection of the player through
QEMU's audio extension, and QEMU converts it to the format of the pipeline. Other machines stay silent. The display and
the sound belong to the player: a configuration that sets `-vnc`, `-audio` or `-audiodev`, or routes the PC speaker
itself with `pcspk-audiodev`, is refused.

QEMU sends the sound about every 10 ms, but refreshes the picture of its VNC display 30 ms after a change at the
earliest, and later when the screen was idle, so the player holds the sound for 70 ms to keep it with the picture. At
most 130 ms of sound wait for the pipeline, the hold included; a slow pipeline loses the oldest sound, so the sound
never falls further behind. QEMU sends nothing while the guest plays no sound, so when the guest stops playing the
player hands silence to the pipeline instead, for two seconds at most: a pause between two sounds keeps its length.
While the player is paused, it drops the sound of the guest. The player reports a sound connection it cannot make to
the exception handler, and the machine runs without sound.

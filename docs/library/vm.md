# Virtualization Module

```{warning}
You must install QEMU yourself to use the virtual machine player; MCAV never installs it. Install it from your
package manager or follow the steps [here](https://www.qemu.org/download/), and make sure the `qemu-system-*`
programs are on the `PATH`.
```

One of the most unique features of MCAV is streaming virtual machines. Add the `mcav-vm` module, which depends on the
`mcav-vnc` module, and install both modules when you create the library instance.

```kotlin
dependencies {
    implementation("me.brandonli:mcav-vm:1.0.0-SNAPSHOT")
}
```

```java
  final MCAVApi api = MCAV.api();
  api.install(VNCModule.class, VMModule.class);
  final VMModule vmModule = api.getModule(VMModule.class);
  if (!vmModule.isQemuInstalled()) {
    // QEMU is missing, virtual machines cannot be started
  }
```

The QEMU command line is built with a `VMConfiguration`, and a `VMSettings` describes how the machine is streamed:
the size frames are scaled to and the frame rate requested from QEMU. The player adds the display, VNC, and pointer
options itself, and picks the fastest accelerator of the machine (KVM, WHPX, or HVF), falling back to software
emulation when the accelerator is unavailable.

The example boots an ISO image and presses a key in its boot menu. The `display` filter is yours and shows the frames;
release the returned player to stop the machine.

```java
  public static VMPlayer bootIsoImage(final Path isoFile, final VideoFilter display) {
    final VideoPipelineStep pipeline = VideoPipelineStep.of(display);

    final String isoPath = isoFile.toString();
    final VMConfiguration configuration = VMConfiguration.builder();
    configuration.cdrom(isoPath);
    configuration.memory(2048);
    configuration.cores(2);

    final VMSettings settings = VMSettings.of(1024, 768, 30);
    final VMPlayer player = VMPlayer.create();
    final VideoAttachableCallback videoCallback = player.getVideoAttachableCallback();
    videoCallback.attach(pipeline);

    player.start(settings, VMPlayer.Architecture.X86_64, configuration);
    player.sendMouseEvent(MouseClick.LEFT, 512, 384);
    player.sendKeyEvent("Return");
    return player;
  }
```

Options that QEMU accepts more than once, such as `-drive` or `-device`, are added with `drive(...)`, `device(...)`, or
`repeatable(key, value)`; every other option replaces its earlier value. `start` throws an
`ExecutableNotInPathException` when the QEMU program of the architecture is not installed, and a `PlayerException`
with the output of QEMU when the machine fails to start.

Behind the scenes, the player starts QEMU with a VNC display bound to the local machine and connects the `mcav-vnc`
player to it. **You are responsible for giving QEMU a valid configuration**; MCAV reports QEMU's errors but does not
try to fix them.

## Sound

The player hands the sound of the guest to its audio pipeline, in the format every audio filter of MCAV takes: 16-bit
little-endian stereo PCM at 48 kHz. Attach a pipeline like the video one:

```java
    final AudioAttachableCallback audioCallback = player.getAudioAttachableCallback();
    audioCallback.attach(AudioPipelineStep.of(speakers));
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
never falls further behind. While the player is paused, the sound of the guest is dropped. A sound connection that
cannot be made is reported to the exception handler, and the machine runs without sound.

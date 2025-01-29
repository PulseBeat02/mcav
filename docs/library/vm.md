# Virtualization Module

```{warning}
You must have QEMU installed in order to use virtual machine player. I do not have plans of bundling QEMU inside of mcav or automatically installing it for users. Unlike VLC and FFmpeg, QEMU comes in many static binaries for each architecture, which would be a nightmare to statically compile. As a result, you must follow the steps [here](https://www.qemu.org/download/) to download and install QEMU into your PATH.
```

One of the most unique features of MCAV is the ability to capture virtual machines. To do this, you must import the
`mcav-vm` module, which will give you access to the virtual machine players.

```kotlin
dependencies {
    implementation("me.brandonli:mcav-vm:1.0.0-SNAPSHOT")
}
```

The virtual machine module takes advantage of [QEMU](https://www.qemu.org/download/) to virtualize. Virtual machines are
incredibly complex, and QEMU supplies many options. MCAV allows you to build these options in a `VMConfiguration`. You
must also create a `VMSettings` to specify other non-QEMU related options.

```java
  final VideoPipelineStep pipeline = ...;
  final VMConfiguration config = VMConfiguration.builder().cdrom(isoPath).memory(2048);
  final VMSettings settings = VMSettings.of(600, 800, 120);
  this.vmPlayer.start(pipeline, settings, VMPlayer.Architecture.X86_64, config);
  // ... do some play back
  player.release();
```

Behind the scenes, it starts a QEMU slave process with VNC enabled. By using the `mcav-vnc` module and the VNC players,
it connects to the virtual machine and provides frames for you. **You are on your own for making sure that the virtual
machine is properly configured.** MCAV will make no attempt to contain or fix any virtual machine errors for whatever
arguments you pass into QEMU.
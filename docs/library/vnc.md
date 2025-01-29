# VNC Module

MCAV provides a VNC player in a separate module called `mcav-vnc`. To use the VNC player, you must import the MCAV vnc
module.

```kotlin
dependencies {
    implementation("me.brandonli:mcav-vnc:1.0.0-SNAPSHOT")
}
```

MCAV also provides a `VNCPlayer`, which is a video player that connects to a VNC server and displays the screen. This is
useful for remote desktop applications or for monitoring remote systems. To use the `VNCPlayer`, you must construct
a `VNCSource`, and pass it to the player.

```java
  final VideoPipelineStep pipeline = ...;
  final VNCSource source = VNCSource.vnc()
    .host("localhost")
    .port(5900)
    .password("passwd")
    .screenWidth(1920)
    .screenHeight(1080)
    .targetFrameRate(30)
    .build();
  
  final VNCPlayer player = VNCPlayer.vm();
  final VideoAttachableCallback videoCallback = player.getVideoAttachableCallback();
  videoCallback.attach(pipeline);
  
  player.start(source);
  // ... do some play back
  player.release();
```

```{note}
The `targetFrameRate` provided in the builder is just a target frame rate, but the machine hosting doesn't have to listen to that frame rate and may completely ignore it.
```

To send key input, you would use the `type` input. Note that unlike the browser player, this does not replace special
keys with their respective keycodes! You must manually parse them on your own. To send mouse input, use the
`updateMouseButton`. The integer
mouse value can be found in the table below.

| Mouse Button Number | Mouse Type/Function      |
|---------------------|--------------------------|
| 1                   | Left button              |
| 2                   | Middle button (wheel)    |
| 3                   | Right button             |
| 4                   | Wheel up (scroll up)     |
| 5                   | Wheel down (scroll down) |
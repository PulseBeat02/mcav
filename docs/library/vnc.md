# VNC Module

MCAV provides a VNC player in a separate module called `mcav-vnc`. Add the module and install it when you create the
library instance.

```kotlin
dependencies {
    implementation("me.brandonli:mcav-vnc:1.0.0-SNAPSHOT")
}
```

```java
  final MCAVApi api = MCAV.api();
  api.install(VNCModule.class);
```

The `VNCPlayer` connects to a VNC server and streams its screen, which is useful for remote desktops or for monitoring
remote systems. Describe the server with a `VNCSource`: the host is required, the port defaults to 5900, and the frame
size defaults to the size of the remote screen.

The example reads the password from an environment variable, so that no secret ends up in the source. The `display`
filter is yours and shows the frames; release the returned player to disconnect.

```java
  public static VNCPlayer streamLocalDesktop(final VideoFilter display) {
    final VideoPipelineStep pipeline = VideoPipelineStep.of(display);

    final VNCSource.Builder builder = VNCSource.builder();
    builder.host("localhost");
    builder.port(5900);
    final String password = System.getenv("VNC_PASSWORD");
    if (password != null) {
      builder.password(password);
    }
    builder.screenWidth(1920);
    builder.screenHeight(1080);
    builder.targetFrameRate(30);
    final VNCSource source = builder.build();

    final VNCPlayer player = VNCPlayer.create();
    final VideoAttachableCallback videoCallback = player.getVideoAttachableCallback();
    videoCallback.attach(pipeline);

    player.start(source);
    return player;
  }
```

```{note}
The frame rate is a request; the server sends fewer frames when the screen does not change.
```

`start` throws a `PlayerException` when the server cannot be reached. Pausing keeps the connection open and only stops
delivering frames.

To send input, use `moveMouse`, `sendMouseEvent`, and `sendKeyEvent`. Coordinates are in the coordinate system of the
streamed frames and are translated to the remote screen. `sendKeyEvent` presses a key when given the name of an X11
keysym, such as `Return`, `Escape`, or `Left`, and types any other text character by character.

| `MouseClick` | Effect on the server                  |
|--------------|---------------------------------------|
| `LEFT`       | Clicks the left button                |
| `RIGHT`      | Clicks the right button               |
| `DOUBLE`     | Double-clicks the left button         |
| `HOLD`       | Presses and holds the left button     |
| `RELEASE`    | Releases the left button              |

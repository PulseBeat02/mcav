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
  api.install(VncModule.class);
```

The `VncPlayer` connects to a VNC server and streams its screen, which is useful for remote desktops or for monitoring
remote systems. Describe the server with a `VncSource`: the host is required, the port defaults to 5900, and the frame
size defaults to the size of the remote screen.

The example reads the password from an environment variable, so that no secret ends up in the source. The `display`
filter is yours and shows the frames; release the returned player to disconnect.

```java
  public VncPlayer streamLocalDesktop(final VideoFilter display) {
    final VideoPipelineStep pipeline = VideoPipelineStep.of(display);

    final String host = "localhost";
    final int width = 1920;
    final int height = 1080;
    final VncSourceBuilder builder = VncSource.builder();
    builder.host(host).port(VncSource.DEFAULT_PORT).screenWidth(width).screenHeight(height);

    final String passwordVariable = "VNC_PASSWORD";
    final @Nullable String password = System.getenv(passwordVariable);
    if (password != null) {
      builder.password(password);
    }

    final VncSource source = builder.build();

    final VncPlayer player = VncPlayer.create();
    final VideoAttachableCallback videoCallback = player.getVideoAttachableCallback();
    videoCallback.attach(pipeline);

    player.start(source);

    return player;
  }
```

```{note}
The frame rate is a request; the server sends fewer frames when the screen does not change.
```

`start` throws a `VncException` when the server cannot be reached. Pausing keeps the connection open and only stops
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

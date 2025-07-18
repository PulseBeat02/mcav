# LWJGL Module

MCAV provides a [LWJGL](https://www.lwjgl.org/) module called `mcav-lwjgl`. To use the LWJGL player, you must import the MCAV
module. 

```kotlin
dependencies {
    implementation("me.brandonli:mcav-lwjgl:1.0.0-SNAPSHOT")
}
```

The LWJGL module provides a class called `GLTextureFilter` which takes incoming video frames and applies them onto a
GL texture. You can then get the texture ID and use it in your OpenGL context.

```java
final GLTextureFilter glTextureFilter = new GLTextureFilter();
glTextureFilter.start();

final VideoPipelineStep videoPipelineStep = VideoPipelineStep.of(glTextureFilter);
final VideoAttachableCallback callback = browser.getVideoAttachableCallback();
callback.attach(videoPipelineStep);

final VideoPlayerMultiplexer player = VideoPlayer.vlc();
player.start(...);

// do some playback...

player.release();
glTextureFilter.release();
```

This is useful for many scenarios, some for example if you want to render a video into a mod.
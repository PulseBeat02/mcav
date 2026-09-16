# LWJGL Module

MCAV provides an [LWJGL](https://www.lwjgl.org/) module called `mcav-lwjgl` that streams video into OpenGL textures,
for example to render a video inside a game or a mod.

```kotlin
dependencies {
    implementation("me.brandonli:mcav-lwjgl:1.0.0-SNAPSHOT")
}
```

OpenGL may only be called from the thread that owns the context, but filters run on the thread of the player. The
`GLTextureFilter` therefore only copies each frame into a staging buffer, and the render thread uploads the newest
frame by calling `upload()` once per rendered frame. `start()` and `release()` must be called on the render thread as
well.

```java
  // call on the render thread, with the OpenGL context current
  public static void renderVideo(final Source source, final BooleanSupplier keepRendering) {
    final GLTextureFilter texture = new GLTextureFilter();
    texture.start();

    final VideoPipelineStep videoPipelineStep = VideoPipelineStep.of(texture);
    final VideoPlayerMultiplexer player = VideoPlayer.ffmpeg();
    final VideoAttachableCallback callback = player.getVideoAttachableCallback();
    callback.attach(videoPipelineStep);
    player.start(source);

    while (keepRendering.getAsBoolean()) {
      texture.upload();
      final int textureId = texture.getTextureId();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
      // draw a textured quad of texture.getWidth() by texture.getHeight() pixels
    }

    player.release();
    texture.release();
  }
```

Frames are uploaded in `GL_BGR` order, the pixel layout of the pipeline, so no conversion is needed. To stream into a
texture you created yourself, pass its name to `new GLTextureFilter(textureId)`; the filter then leaves deleting it to
you.

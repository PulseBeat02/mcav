# Image Manipulation

MCAV represents images with `ImageBuffer`, the same type the video pipeline passes to filters, so every video filter
can also be applied to a single image. Images are backed by [OpenCV](https://opencv.org/) matrices, which makes the
filters fast.

```{warning}
An `ImageBuffer` holds native memory. Release it with `release()` or a try-with-resources block when you are done,
otherwise the memory is only freed when the garbage collector finds the object.
```

```java
  public static BufferedImage createInvertedThumbnail(final BufferedImage original) {
    try (final ImageBuffer image = ImageBuffer.image(original)) {
      final ResizeFilter resize = new ResizeFilter(200, 200);
      resize.applyFilter(image);
      final InvertFilter invert = new InvertFilter();
      invert.applyFilter(image);
      final BufferedImage thumbnail = image.toBufferedImage();
      return thumbnail;
    }
  }
```

Images can also be loaded from files and URLs with `ImageBuffer.path(FileSource)` and `ImageBuffer.uri(UriSource)`,
or created from raw pixels with `ImageBuffer.buffer(int[], width, height)`.

## Reading Pixels

`ImageBuffer` offers the pixels as packed ARGB integers, laid out row by row, in three ways:

| Method                | Returns                                                                                          |
|-----------------------|--------------------------------------------------------------------------------------------------|
| `getPixels()`         | The shared, cached `int[]`, without copying. Treat it as read-only.                              |
| `copyPixels()`        | A new `int[]` that belongs to you: modify and keep it freely.                                    |
| `getReadOnlyPixels()` | A read-only `IntBuffer` view of the shared array, without copying. Writes throw a `ReadOnlyBufferException`. |

`getPixels()` is meant for code that reads every frame, where copying would be too slow. Every caller receives the
same array until the image changes, so writing into it does not change the image but silently changes what every later
caller sees, including every later loop of a `RepeatingFrameSource`. To change pixels, modify a copy and write it back
with `updateArgb(int[], width, height)`:

```java
  public static void paintFirstPixelRed(final Path imageFile) {
    final FileSource file = FileSource.path(imageFile);
    try (final ImageBuffer image = ImageBuffer.path(file)) {
      final int[] pixels = image.getPixels(); // shared: read it, never write it
      final int firstPixel = pixels[0];
      System.out.printf("The first pixel was %08X%n", firstPixel);

      final int[] editable = image.copyPixels(); // your own copy: write it freely
      editable[0] = 0xFFFF0000;
      final int width = image.getWidth();
      final int height = image.getHeight();
      image.updateArgb(editable, width, height);

      final IntBuffer readOnlyView = image.getReadOnlyPixels(); // no copy, rejects writes
      final int updatedFirstPixel = readOnlyView.get(0);
      System.out.printf("The first pixel is now %08X%n", updatedFirstPixel);
    }
  }
```

## Loading GIFs

`DynamicImageBuffer` decodes every frame of an animated GIF from a file or URL and reports its frame rate. Play it
with the [image player](player.md#image-players).

```java
  public static void describeGif() throws IOException {
    final URI sourceUri = URI.create("https://example.com/image.gif");
    final UriSource source = UriSource.uri(sourceUri);
    try (final DynamicImageBuffer gif = DynamicImageBuffer.uri(source)) {
      final float frameRate = gif.getFrameRate();
      final List<ImageBuffer> frames = gif.getFrames();
      final int frameCount = frames.size();
      System.out.println(frameCount + " frames at " + frameRate + " frames per second");
    }
  }
```

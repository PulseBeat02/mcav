# Image Manipulation

MCAV represents images with `ImageBuffer`, the same type the video pipeline passes to filters, so every video filter
can also be applied to a single image. Images are backed by [OpenCV](https://opencv.org/) matrices, which makes the
filters fast.

```{warning}
An `ImageBuffer` holds native memory. Release it with `release()` or a try-with-resources block when you are done,
otherwise the memory is only freed when the garbage collector finds the object.
```

```java
  public BufferedImage createInvertedThumbnail(final BufferedImage original) {
    try (final ImageBuffer image = ImageBuffer.image(original)) {
      final int thumbnailSize = 200;
      final ResizeFilter resize = new ResizeFilter(thumbnailSize, thumbnailSize);
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

OpenCV allocates the whole picture as soon as it has read a file's header, so a small file can make it take gigabytes.
`ImageBuffer.bytes`, `path` and `uri` therefore read the header first and refuse, with an `IllegalArgumentException`,
an image that declares more than 67,108,864 pixels (8192 by 8192). The system property `mcav.image.maxPixels` sets
another limit, and `0` turns the check off. The header is read for the formats OpenCV decodes: BMP, GIF, JPEG, PNG,
WebP, TIFF, Radiance HDR, Sun raster and PBM, PGM, PPM, PAM and PFM. JPEG 2000 and OpenEXR, which OpenCV turns off
unless an environment variable turns them on, are refused while the check is on.

## Reading pixels

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
  public void paintFirstPixelRed(final Path imageFile) {
    final FileSource file = FileSource.path(imageFile);
    try (final ImageBuffer image = ImageBuffer.path(file)) {
      final int[] pixels = image.getPixels();
      final int firstPixel = pixels[0];
      final String firstColor = Integer.toHexString(firstPixel);
      LOGGER.debug("The first pixel was {}.", firstColor);

      final int[] editable = image.copyPixels();
      final int opaqueRed = 0xFFFF0000;
      editable[0] = opaqueRed;
      final int width = image.getWidth();
      final int height = image.getHeight();
      image.updateArgb(editable, width, height);

      final IntBuffer readOnlyView = image.getReadOnlyPixels();
      final int updatedFirstPixel = readOnlyView.get(0);
      final String updatedColor = Integer.toHexString(updatedFirstPixel);
      LOGGER.debug("The first pixel is now {}.", updatedColor);
    }
  }
```

## Loading GIFs

`DynamicImageBuffer` decodes every frame of an animated GIF from a file or URL and reports its frame rate. Play it
with the [image player](player.md#image-players).

```java
  public void describeGif() {
    final String address = "https://example.com/image.gif";
    final URI sourceUri = URI.create(address);
    final UriSource source = UriSource.uri(sourceUri);
    try (final DynamicImageBuffer gif = DynamicImageBuffer.uri(source)) {
      final float frameRate = gif.getFrameRate();
      final List<ImageBuffer> frames = gif.getFrames();
      final int frameCount = frames.size();
      LOGGER.debug("The GIF has {} frames at {} frames per second.", frameCount, frameRate);
    }
  }
```

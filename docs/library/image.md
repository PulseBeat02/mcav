# Image Manipulation

MCAV allows users to easily manipulate images using the `Image` class. This class provides a variety of methods for
creating, transforming, and rendering images. By default, this is what is used in the video pipeline used by players for
easy pipeline manipulation. For static images, use the `ImageBuffer` class.

These utility methods use [OpenCV](https://opencv.org/) to perform matrix operations, which allows for efficient image
processing.

```{warning}
After you are done using the image, you **must** call the close() method to release all native resources. Otherwise,
your program will have a memory leak and might cause unexpected behavior.
```

```java
  final BufferedImage buffered = ...;
  final ImageBuffer image = ImageBuffer.image(buffered);
  final ResizeFilter resize = ResizeFilter.resize(200, 200);
  image.applyFilter(resize);
  // and more operations
  image.close();
```

## Loading Gifs

MCAV also supports loading GIFs as images. The `DynamicImageBuffer` class allows you to load a GIF from a file or URL abd
provides methods to manipulate the GIF frames or getting the frame rate.

```java
  final UriSource source = UriSource.uri(URI.create("https://example.com/image.gif"));
  final DynamicImageBuffer image = DynamicImageBuffer.uri(source);
  final float fps = image.getFrameRate();
  // and more operations
  image.close();
```
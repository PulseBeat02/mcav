package me.brandonli.mcav.utils;

import java.io.*;

import static java.util.Objects.requireNonNull;

public final class NativeUtils {

  private NativeUtils() {
    throw new UnsupportedOperationException("Utility class cannot be instantiated");
  }

  /**
   * Loads a native library from the JAR resources.
   * The library is extracted to a temporary file and then loaded.
   *
   * @param resource The path to the library within the JAR resources
   */
  public static void loadLibrary(final String resource) {
    try {
      final String filename = resource.contains("/") ? resource.substring(resource.lastIndexOf('/') + 1) : resource;
      final String prefix = filename.substring(0, filename.lastIndexOf('.'));
      final String suffix = filename.substring(filename.lastIndexOf('.'));
      final File tempFile = File.createTempFile(prefix, suffix);
      tempFile.deleteOnExit();
      try (final InputStream in = requireNonNull(NativeUtils.class.getClassLoader()).getResourceAsStream(resource); final OutputStream out = new FileOutputStream(tempFile)) {
        if (in == null) {
          throw new IOException("Resource not found: " + resource);
        }
        final byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
          out.write(buffer, 0, bytesRead);
        }
        out.flush();
      }
      System.load(tempFile.getAbsolutePath());
    } catch (final IOException e) {
      throw new RuntimeException("Failed to load native library: " + resource, e);
    }
  }
}

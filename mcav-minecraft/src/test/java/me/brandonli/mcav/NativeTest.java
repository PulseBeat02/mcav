package me.brandonli.mcav;

public final class NativeTest {

  public static void main(final String[] args) {
    new FilterLiteDither().ditherExampleNative(new int[1000000], 1000, new int[1000], new byte[1000]);
  }

  public static class FilterLiteDither {

    static {
      try {
        System.load("C:\\Users\\brand\\IdeaProjects\\mcav\\cpp-src\\output\\filterlite-win64.dll");
      } catch (final UnsatisfiedLinkError e) {
        System.err.println("Failed to load native library: " + e.getMessage());
      }
    }

    public native byte[] ditherExampleNative(int[] buffer, int width, int[] colors, byte[] mapColors);
  }
}

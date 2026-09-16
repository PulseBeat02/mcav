/*
 * This file is part of mcav, a media playback library for Java
 * Copyright (C) Brandon Li <https://brandonli.me/>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package me.brandonli.mcav.lwjgl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import me.brandonli.mcav.lwjgl.testing.Await;
import me.brandonli.mcav.media.image.ImageBuffer;
import me.brandonli.mcav.media.player.metadata.OriginalVideoMetadata;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.system.MemoryUtil;
import org.mockito.stubbing.Stubber;

/**
 * Tests {@link GLTextureFilter} against a real OpenGL context of a hidden GLFW window, and reads the textures back
 * to check what was uploaded. The tests are skipped on machines without a display, an OpenGL driver, or LWJGL natives
 * for the platform, and on macOS when the tests do not run on the first thread.
 */
final class GLTextureFilterTest {

  private static boolean initialized;
  private static long window;

  private GLTextureFilter filter;

  @BeforeAll
  static void createContext() {
    try {
      initialized = GLFW.glfwInit();
      assumeTrue(initialized, "GLFW cannot be initialized on this machine");
      GLFW.glfwDefaultWindowHints();
      GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
      window = GLFW.glfwCreateWindow(16, 16, "mcav-test", MemoryUtil.NULL, MemoryUtil.NULL);
      assumeTrue(window != MemoryUtil.NULL, "no OpenGL window can be created on this machine");
      GLFW.glfwMakeContextCurrent(window);
      GL.createCapabilities();
    } catch (final IllegalStateException | LinkageError exception) {
      // missing or wrong natives, no OpenGL library, or GLFW on macOS off the first thread
      abort("OpenGL is not available on this machine: " + exception);
    }
  }

  @AfterAll
  static void destroyContext() {
    if (window != MemoryUtil.NULL) {
      GL.setCapabilities(null);
      GLFW.glfwMakeContextCurrent(MemoryUtil.NULL);
      GLFW.glfwDestroyWindow(window);
      window = MemoryUtil.NULL;
    }
    if (initialized) {
      GLFW.glfwTerminate();
      initialized = false;
    }
  }

  @BeforeEach
  void createFilter() {
    this.filter = new GLTextureFilter();
  }

  @AfterEach
  void releaseFilter() {
    this.filter.release();
  }

  private static ImageBuffer frame(final int width, final int height, final int argb) {
    final int[] pixels = new int[width * height];
    Arrays.fill(pixels, argb);
    return ImageBuffer.buffer(pixels, width, height);
  }

  private static byte[] readTexture(final int texture, final int width, final int height) {
    final ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 3);
    try {
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
      GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
      GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, pixels);
      GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      final byte[] bytes = new byte[width * height * 3];
      pixels.get(bytes);
      return bytes;
    } finally {
      MemoryUtil.memFree(pixels);
    }
  }

  private static int parameter(final int texture, final int name) {
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
    final int value = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, name);
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    return value;
  }

  private static int levelParameter(final int texture, final int name) {
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
    final int value = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, name);
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    return value;
  }

  private static void setUnpackState(final int alignment, final int rowLength, final int skipRows, final int skipPixels) {
    GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, alignment);
    GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, rowLength);
    GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, skipRows);
    GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, skipPixels);
  }

  private static int[] readUnpackState() {
    final int alignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
    final int rowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
    final int skipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS);
    final int skipPixels = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
    return new int[] { alignment, rowLength, skipRows, skipPixels };
  }

  private static void apply(final GLTextureFilter filter, final ImageBuffer image) {
    final int width = image.getWidth();
    final int height = image.getHeight();
    final OriginalVideoMetadata metadata = OriginalVideoMetadata.of(width, height);
    final boolean kept = filter.applyFilter(image, metadata);
    assertTrue(kept, "the filter never drops frames");
  }

  // the frame is released before the upload, since the filter must have copied it already
  private static void stage(final GLTextureFilter filter, final int width, final int height, final int argb) {
    try (final ImageBuffer image = frame(width, height, argb)) {
      apply(filter, image);
    }
  }

  private static boolean stageAndUpload(final GLTextureFilter filter, final int width, final int height, final int argb) {
    stage(filter, width, height, argb);
    return filter.upload();
  }

  private static void assertAllPixels(final byte[] pixels, final byte[] rgb) {
    final int count = pixels.length / 3;
    for (int pixel = 0; pixel < count; pixel++) {
      final byte[] actual = { pixels[pixel * 3], pixels[pixel * 3 + 1], pixels[pixel * 3 + 2] };
      assertArrayEquals(rgb, actual, "pixel " + pixel);
    }
  }

  private static Throwable failureOnAnotherThread(final Runnable action) throws InterruptedException {
    final List<Throwable> failures = new CopyOnWriteArrayList<>();
    final Thread other = new Thread(() -> {
      try {
        action.run();
      } catch (final RuntimeException exception) {
        failures.add(exception);
      }
    });
    other.start();
    other.join(10_000L);
    final int failureCount = failures.size();
    assertEquals(1, failureCount, failures::toString);
    return failures.getFirst();
  }

  private static boolean await(final CountDownLatch latch, final long seconds) {
    try {
      return latch.await(seconds, TimeUnit.SECONDS);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();
      return false;
    }
  }

  private static void startThread(final Runnable action) {
    final Thread thread = new Thread(action);
    thread.start();
  }

  @Test
  void createsATextureWithLinearFilteringAndClampedEdges() {
    final int before = this.filter.getTextureId();
    this.filter.start();
    final int texture = this.filter.getTextureId();
    final boolean isTexture = GL11.glIsTexture(texture);
    final int minFilter = parameter(texture, GL11.GL_TEXTURE_MIN_FILTER);
    final int magFilter = parameter(texture, GL11.GL_TEXTURE_MAG_FILTER);
    final int wrapS = parameter(texture, GL11.GL_TEXTURE_WRAP_S);
    final int wrapT = parameter(texture, GL11.GL_TEXTURE_WRAP_T);
    final int width = this.filter.getWidth();
    assertEquals(0, before);
    assertNotEquals(0, texture);
    assertTrue(isTexture);
    assertEquals(GL11.GL_LINEAR, minFilter);
    assertEquals(GL11.GL_LINEAR, magFilter);
    assertEquals(GL12.GL_CLAMP_TO_EDGE, wrapS);
    assertEquals(GL12.GL_CLAMP_TO_EDGE, wrapT);
    assertEquals(0, width);
  }

  @Test
  void uploadsTheNewestFrameWithItsColors() {
    this.filter.start();
    final boolean nothingYet = this.filter.upload();
    stage(this.filter, 3, 2, 0xFFFF0000);
    stage(this.filter, 3, 2, 0xFF0000FF);

    final boolean pending = this.filter.hasPendingFrame();
    final boolean uploaded = this.filter.upload();
    final boolean pendingAfter = this.filter.hasPendingFrame();
    final boolean uploadedAgain = this.filter.upload();
    final int texture = this.filter.getTextureId();
    final int width = this.filter.getWidth();
    final int height = this.filter.getHeight();
    final byte[] pixels = readTexture(texture, 3, 2);
    assertFalse(nothingYet);
    assertTrue(pending);
    assertTrue(uploaded);
    assertFalse(pendingAfter);
    assertFalse(uploadedAgain);
    assertEquals(3, width);
    assertEquals(2, height);
    assertAllPixels(pixels, new byte[] { 0, 0, (byte) 0xFF });
  }

  @Test
  void updatesFramesOfTheSameSizeInPlaceAndResizesForNewSizes() {
    this.filter.start();
    stageAndUpload(this.filter, 5, 3, 0xFF00FF00);
    stageAndUpload(this.filter, 5, 3, 0xFFFFFFFF);
    final int texture = this.filter.getTextureId();
    final byte[] white = readTexture(texture, 5, 3);

    stageAndUpload(this.filter, 7, 4, 0xFF000000);
    final int width = this.filter.getWidth();
    final int height = this.filter.getHeight();
    final byte[] black = readTexture(texture, 7, 4);
    final int textureWidth = levelParameter(texture, GL11.GL_TEXTURE_WIDTH);
    assertAllPixels(white, new byte[] { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF });
    assertAllPixels(black, new byte[] { 0, 0, 0 });
    assertEquals(7, width);
    assertEquals(4, height);
    assertEquals(7, textureWidth);
  }

  @Test
  void resizesTheTextureWhenOnlyTheHeightChanges() {
    this.filter.start();
    stageAndUpload(this.filter, 5, 3, 0xFF00FF00);
    final boolean uploaded = stageAndUpload(this.filter, 5, 6, 0xFFFF0000);
    final int texture = this.filter.getTextureId();
    final int height = this.filter.getHeight();
    final int textureHeight = levelParameter(texture, GL11.GL_TEXTURE_HEIGHT);
    final byte[] pixels = readTexture(texture, 5, 6);
    assertTrue(uploaded);
    assertEquals(6, height);
    assertEquals(6, textureHeight);
    assertAllPixels(pixels, new byte[] { (byte) 0xFF, 0, 0 });
  }

  @Test
  void resizesTheTextureWhenOnlyTheWidthChanges() {
    this.filter.start();
    stageAndUpload(this.filter, 5, 3, 0xFF00FF00);
    // only the width changes, so the texture is reallocated even though the height still matches
    final boolean uploaded = stageAndUpload(this.filter, 7, 3, 0xFF0000FF);
    final int texture = this.filter.getTextureId();
    final int width = this.filter.getWidth();
    final int textureWidth = levelParameter(texture, GL11.GL_TEXTURE_WIDTH);
    final byte[] pixels = readTexture(texture, 7, 3);
    assertTrue(uploaded);
    assertEquals(7, width);
    assertEquals(7, textureWidth);
    assertAllPixels(pixels, new byte[] { 0, 0, (byte) 0xFF });
  }

  @Test
  void leavesTheOpenGlStateOfTheCallerAsItWas() {
    final int callerTexture = GL11.glGenTextures();
    this.filter.start();
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, callerTexture);
    setUnpackState(8, 16, 2, 3);
    stageAndUpload(this.filter, 3, 2, 0xFF00FF00);
    final int boundAfterUpload = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    final int[] unpackState = readUnpackState();

    // the upload itself ignores the unpack settings of the caller, so the whole frame arrives unshifted
    setUnpackState(4, 0, 0, 0);
    final int texture = this.filter.getTextureId();
    final byte[] pixels = readTexture(texture, 3, 2);

    GL11.glBindTexture(GL11.GL_TEXTURE_2D, callerTexture);
    this.filter.start();
    final int boundAfterStart = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    assertEquals(callerTexture, boundAfterUpload);
    assertArrayEquals(new int[] { 8, 16, 2, 3 }, unpackState);
    assertEquals(callerTexture, boundAfterStart);
    assertAllPixels(pixels, new byte[] { 0, (byte) 0xFF, 0 });
    GL11.glDeleteTextures(callerTexture);
  }

  @Test
  void acceptsTheNextFrameWhileAFrameIsUploaded() {
    final CountDownLatch uploading = new CountDownLatch(1);
    final CountDownLatch finishUpload = new CountDownLatch(1);
    final CountDownLatch applied = new CountDownLatch(1);
    final GLTextureFilter slow = slowFilter(uploading, finishUpload);
    slow.start();
    stage(slow, 2, 2, 0xFFFF0000);
    startThread(() -> stageOnceUploading(slow, uploading, applied));
    // the player must get its frame in while the render thread is still uploading
    startThread(() -> finishUploadOnceApplied(applied, finishUpload));

    final long before = System.nanoTime();
    final boolean first = slow.upload();
    final long elapsedNanos = System.nanoTime() - before;
    final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
    final boolean appliedInTime = await(applied, 10);
    final boolean pending = slow.hasPendingFrame();
    final boolean second = slow.upload();
    final int texture = slow.getTextureId();
    final byte[] pixels = readTexture(texture, 2, 2);
    slow.release();
    assertTrue(first);
    assertTrue(appliedInTime);
    assertTrue(elapsedMillis < 4_000L, () -> "the player was blocked by the upload for " + elapsedMillis + " ms");
    assertTrue(pending);
    assertTrue(second);
    assertAllPixels(pixels, new byte[] { 0, 0, (byte) 0xFF });
  }

  private static GLTextureFilter slowFilter(final CountDownLatch uploading, final CountDownLatch finishUpload) {
    return new GLTextureFilter() {
      @Override
      void transfer(final ByteBuffer pixels, final int width, final int height) {
        uploading.countDown();
        final boolean finished = await(finishUpload, 10);
        assertTrue(finished, "the upload was never allowed to finish");
        super.transfer(pixels, width, height);
      }
    };
  }

  private static void stageOnceUploading(final GLTextureFilter filter, final CountDownLatch uploading, final CountDownLatch applied) {
    final boolean uploadStarted = await(uploading, 10);
    if (uploadStarted) {
      stage(filter, 2, 2, 0xFF0000FF);
      applied.countDown();
    }
  }

  // releases the upload once the player staged its frame, or after five seconds so a failing test does not hang
  private static void finishUploadOnceApplied(final CountDownLatch applied, final CountDownLatch finishUpload) {
    await(applied, 5);
    finishUpload.countDown();
  }

  @Test
  void refusesOpenGlCallsWithoutACurrentContext() throws InterruptedException {
    final int borrowedTexture = GL11.glGenTextures();
    final GLTextureFilter borrowing = new GLTextureFilter(borrowedTexture);
    this.filter.start();
    final Throwable start = failureOnAnotherThread(borrowing::start);
    final Throwable upload = failureOnAnotherThread(borrowing::upload);
    final Throwable release = failureOnAnotherThread(this.filter::release);
    final int texture = this.filter.getTextureId();
    for (final Throwable failure : List.of(start, upload, release)) {
      assertInstanceOf(IllegalStateException.class, failure);
      final String message = failure.getMessage();
      final boolean explains = message.startsWith("No OpenGL context is current on this thread");
      assertTrue(explains, message);
    }
    assertNotEquals(0, texture, "a failed release keeps the texture so it can be released on the render thread");
    GL11.glDeleteTextures(borrowedTexture);
  }

  @Test
  void ignoresFramesWithTooLittleData() {
    this.filter.start();
    final ImageBuffer truncated = mock(ImageBuffer.class);
    final Stubber widthStubbing = doReturn(4);
    final ImageBuffer stubbedWidth = widthStubbing.when(truncated);
    stubbedWidth.getWidth();
    final Stubber heightStubbing = doReturn(4);
    final ImageBuffer stubbedHeight = heightStubbing.when(truncated);
    stubbedHeight.getHeight();

    // a 4x4 frame needs 48 bytes
    final ByteBuffer data = ByteBuffer.allocateDirect(47);
    final Stubber dataStubbing = doReturn(data);
    final ImageBuffer stubbedData = dataStubbing.when(truncated);
    stubbedData.getData();
    apply(this.filter, truncated);
    final boolean pending = this.filter.hasPendingFrame();
    assertFalse(pending);
  }

  @Test
  void acceptsFramesFromAnotherThread() throws InterruptedException {
    this.filter.start();
    final Thread player = new Thread(() -> stage(this.filter, 2, 2, 0xFF0000FF));
    player.start();
    Await.until("the frame of the player thread is staged", this.filter::hasPendingFrame);
    player.join(10_000L);
    final boolean uploaded = this.filter.upload();
    assertTrue(uploaded);
  }

  @Test
  void refusesToUploadBeforeStartAndAfterRelease() {
    stage(this.filter, 2, 2, 0xFF0000FF);
    assertThrows(IllegalStateException.class, this.filter::upload);
    this.filter.start();
    this.filter.release();
    final int texture = this.filter.getTextureId();
    final boolean pending = this.filter.hasPendingFrame();
    assertEquals(0, texture);
    assertFalse(pending);
    assertThrows(IllegalStateException.class, this.filter::upload);
  }

  @Test
  void deletesOnlyTexturesItCreated() {
    this.filter.start();
    final int created = this.filter.getTextureId();
    this.filter.release();
    final boolean createdExists = GL11.glIsTexture(created);

    final int existing = GL11.glGenTextures();
    final GLTextureFilter borrowing = new GLTextureFilter(existing);
    borrowing.start();
    final int borrowed = borrowing.getTextureId();
    final boolean uploaded = stageAndUpload(borrowing, 2, 2, 0xFF00FF00);
    borrowing.release();
    borrowing.release();
    final boolean existingKept = GL11.glIsTexture(existing);
    final int afterRelease = borrowing.getTextureId();
    assertFalse(createdExists);
    assertEquals(existing, borrowed);
    assertTrue(uploaded);
    assertTrue(existingKept);
    assertEquals(existing, afterRelease);
    GL11.glDeleteTextures(existing);
  }

  @Test
  void keepsItsTextureWhenStartedAgain() {
    this.filter.start();
    final int first = this.filter.getTextureId();
    stageAndUpload(this.filter, 2, 2, 0xFF00FF00);
    this.filter.start();
    final int second = this.filter.getTextureId();
    final int width = this.filter.getWidth();
    assertEquals(first, second);
    assertEquals(0, width);
  }

  @Test
  void rejectsInvalidArguments() {
    assertThrows(IllegalArgumentException.class, () -> new GLTextureFilter(0));
    assertThrows(IllegalArgumentException.class, () -> new GLTextureFilter(-3));
    final OriginalVideoMetadata metadata = OriginalVideoMetadata.of(1, 1);
    assertThrows(NullPointerException.class, () -> this.filter.applyFilter(null, metadata));
    try (final ImageBuffer image = frame(1, 1, 0xFF000000)) {
      assertThrows(NullPointerException.class, () -> this.filter.applyFilter(image, null));
    }
  }
}

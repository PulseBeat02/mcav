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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
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
import org.mockito.MockedStatic;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Stubber;

/**
 * Tests {@link GlTextureFilter} against a real OpenGL context of a hidden GLFW window, and reads the textures back
 * to check what was uploaded. The tests are skipped on machines without an OpenGL driver or LWJGL natives
 * for the platform, and on macOS when the tests do not run on the first thread.
 */
final class GlTextureFilterTest {

  private static final int WINDOW_SIZE = 16;
  private static final String WINDOW_TITLE = "mcav-test";

  private static final long JOIN_TIMEOUT_MILLISECONDS = 10_000L;
  private static final long LATCH_TIMEOUT_SECONDS = 10L;
  private static final long APPLIED_TIMEOUT_SECONDS = 5L;
  private static final long UPLOAD_LIMIT_MILLISECONDS = 4_000L;

  private static final int NO_TEXTURE = 0;
  private static final int BASE_LEVEL = 0;
  private static final int NO_BORDER = 0;

  private static final int CHANNELS = 3;
  private static final int RED_OFFSET = 0;
  private static final int GREEN_OFFSET = 1;
  private static final int BLUE_OFFSET = 2;

  private static final int PACKED_ALIGNMENT = 1;
  private static final int DEFAULT_ALIGNMENT = 4;
  private static final int DEFAULT_ROW_LENGTH = 0;
  private static final int DEFAULT_SKIP = 0;

  private static final int CALLER_ALIGNMENT = 8;
  private static final int CALLER_ROW_LENGTH = 16;
  private static final int CALLER_SKIP_ROWS = 2;
  private static final int CALLER_SKIP_PIXELS = 3;

  private static final int RED_ARGB = 0xFFFF0000;
  private static final int GREEN_ARGB = 0xFF00FF00;
  private static final int BLUE_ARGB = 0xFF0000FF;
  private static final int WHITE_ARGB = 0xFFFFFFFF;
  private static final int BLACK_ARGB = 0xFF000000;

  private static final byte[] RED_RGB = { (byte) 0xFF, 0, 0 };
  private static final byte[] GREEN_RGB = { 0, (byte) 0xFF, 0 };
  private static final byte[] BLUE_RGB = { 0, 0, (byte) 0xFF };
  private static final byte[] WHITE_RGB = { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF };
  private static final byte[] BLACK_RGB = { 0, 0, 0 };

  private static final int SMALL_WIDTH = 3;
  private static final int SMALL_HEIGHT = 2;

  private static final int INITIAL_WIDTH = 5;
  private static final int INITIAL_HEIGHT = 3;
  private static final int RESIZED_WIDTH = 7;
  private static final int RESIZED_HEIGHT = 4;
  private static final int TALLER_HEIGHT = 6;

  private static final int PORTRAIT_WIDTH = 2;
  private static final int PORTRAIT_HEIGHT = 3;

  private static final int SQUARE_SIZE = 2;
  private static final int TRUNCATED_SIZE = 4;
  private static final int SINGLE_PIXEL = 1;

  private static final int NEGATIVE_TEXTURE_ID = -3;

  private static final int FRAME_COUNT = 4;
  private static final int STAGING_BUFFER_COUNT = 2;

  private static final String TEXTURE_IMAGE_METHOD = "glTexImage2D";
  private static final String CONTEXT_FAILURE_PREFIX = "No OpenGL context is current on this thread";

  private static boolean initialized;
  private static long window;

  private GlTextureFilter filter;
  private List<Thread> workers;
  private List<Throwable> workerFailures;

  @BeforeAll
  static void createContext() {
    try {
      window = createWindow(GLFW.GLFW_NATIVE_CONTEXT_API);
      if (window == MemoryUtil.NULL) {
        GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_NULL);
        window = createWindow(GLFW.GLFW_EGL_CONTEXT_API);
      }

      assumeTrue(window != MemoryUtil.NULL, "No OpenGL context can be created on this machine!");

      GLFW.glfwMakeContextCurrent(window);
      GL.createCapabilities();
    } catch (final IllegalStateException | LinkageError exception) {
      final String message = "OpenGL setup failed with %s on this machine!".formatted(exception);
      abort(message);
    }
  }

  private static long createWindow(final int contextApi) {
    initialized = GLFW.glfwInit();
    if (!initialized) {
      return MemoryUtil.NULL;
    }

    GLFW.glfwDefaultWindowHints();
    GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
    GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_CREATION_API, contextApi);
    final long created = GLFW.glfwCreateWindow(WINDOW_SIZE, WINDOW_SIZE, WINDOW_TITLE, MemoryUtil.NULL, MemoryUtil.NULL);

    if (created == MemoryUtil.NULL) {
      GLFW.glfwTerminate();
      initialized = false;
    }

    return created;
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

    GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_ANY_PLATFORM);
  }

  @BeforeEach
  void setUp() {
    this.filter = new GlTextureFilter();
    this.workers = new ArrayList<>();
    this.workerFailures = new CopyOnWriteArrayList<>();
  }

  @AfterEach
  void releaseFilter() throws InterruptedException {
    try {
      for (final Thread worker : this.workers) {
        worker.join(JOIN_TIMEOUT_MILLISECONDS);
        final boolean alive = worker.isAlive();
        assertFalse(alive, "the frame worker finished");
      }

      assertEquals(List.of(), this.workerFailures, "frame worker assertions reach the test thread");
    } finally {
      this.filter.release();
    }
  }

  @Test
  void shouldRestoreCallerStateWhenNativeUploadThrows() {
    this.filter.start();

    final int callerTexture = GL11.glGenTextures();
    final ByteBuffer pixels = ByteBuffer.allocateDirect(CHANNELS);
    final IllegalArgumentException failure = new IllegalArgumentException("Native upload was rejected!");
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, callerTexture);
    GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, CALLER_ALIGNMENT);
    GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, CALLER_ROW_LENGTH);
    GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, CALLER_SKIP_ROWS);
    GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, CALLER_SKIP_PIXELS);

    try {
      try (final MockedStatic<GL11> calls = mockStatic(GL11.class, invocation -> failTextureImage(invocation, failure))) {
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
          this.filter.transfer(pixels, SINGLE_PIXEL, SINGLE_PIXEL)
        );
        assertSame(failure, thrown);

        calls.verify(() -> uploadSinglePixel(pixels));
      }

      final int binding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
      final int alignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
      final int rowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
      final int skipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS);
      final int skipPixels = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
      assertEquals(callerTexture, binding);
      assertEquals(CALLER_ALIGNMENT, alignment);
      assertEquals(CALLER_ROW_LENGTH, rowLength);
      assertEquals(CALLER_SKIP_ROWS, skipRows);
      assertEquals(CALLER_SKIP_PIXELS, skipPixels);
    } finally {
      GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, DEFAULT_ALIGNMENT);
      GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, DEFAULT_ROW_LENGTH);
      GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, DEFAULT_SKIP);
      GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, DEFAULT_SKIP);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, NO_TEXTURE);
      GL11.glDeleteTextures(callerTexture);
    }
  }

  private static Object failTextureImage(final InvocationOnMock invocation, final RuntimeException failure) throws Throwable {
    final Method method = invocation.getMethod();
    final String name = method.getName();
    if (name.equals(TEXTURE_IMAGE_METHOD)) {
      throw failure;
    }

    return invocation.callRealMethod();
  }

  private static void uploadSinglePixel(final ByteBuffer pixels) {
    GL11.glTexImage2D(
      GL11.GL_TEXTURE_2D,
      BASE_LEVEL,
      GL11.GL_RGB8,
      SINGLE_PIXEL,
      SINGLE_PIXEL,
      NO_BORDER,
      GL12.GL_BGR,
      GL11.GL_UNSIGNED_BYTE,
      pixels
    );
  }

  @Test
  void shouldCreateATextureWithLinearFilteringAndClampedEdges() {
    final int before = this.filter.getTextureId();
    assertEquals(NO_TEXTURE, before);

    this.filter.start();

    final int texture = this.filter.getTextureId();
    final boolean isTexture = GL11.glIsTexture(texture);
    assertNotEquals(NO_TEXTURE, texture);
    assertTrue(isTexture);

    final int minificationFilter = parameter(texture, GL11.GL_TEXTURE_MIN_FILTER);
    final int magnificationFilter = parameter(texture, GL11.GL_TEXTURE_MAG_FILTER);
    final int wrapS = parameter(texture, GL11.GL_TEXTURE_WRAP_S);
    final int wrapT = parameter(texture, GL11.GL_TEXTURE_WRAP_T);
    assertEquals(GL11.GL_LINEAR, minificationFilter);
    assertEquals(GL11.GL_LINEAR, magnificationFilter);
    assertEquals(GL12.GL_CLAMP_TO_EDGE, wrapS);
    assertEquals(GL12.GL_CLAMP_TO_EDGE, wrapT);

    final int width = this.filter.getWidth();
    assertEquals(0, width);
  }

  private static int parameter(final int texture, final int name) {
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
    final int value = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, name);
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, NO_TEXTURE);
    return value;
  }

  @Test
  void shouldReplaceNonlinearFilteringOnABorrowedTexture() {
    final int texture = GL11.glGenTextures();
    final GlTextureFilter borrowing = new GlTextureFilter(texture);
    try {
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, NO_TEXTURE);
      borrowing.start();

      final int minificationFilter = parameter(texture, GL11.GL_TEXTURE_MIN_FILTER);
      final int magnificationFilter = parameter(texture, GL11.GL_TEXTURE_MAG_FILTER);
      final int wrapS = parameter(texture, GL11.GL_TEXTURE_WRAP_S);
      final int wrapT = parameter(texture, GL11.GL_TEXTURE_WRAP_T);
      assertEquals(GL11.GL_LINEAR, minificationFilter);
      assertEquals(GL11.GL_LINEAR, magnificationFilter);
      assertEquals(GL12.GL_CLAMP_TO_EDGE, wrapS);
      assertEquals(GL12.GL_CLAMP_TO_EDGE, wrapT);
    } finally {
      borrowing.release();
      GL11.glDeleteTextures(texture);
    }
  }

  @Test
  void shouldReuseTheTwoStagingBuffersForFixedSizePlayback() {
    final List<ByteBuffer> transferred = new ArrayList<>();
    final GlTextureFilter recording = recordingFilter(transferred);
    try {
      recording.start();

      for (int frame = 0; frame < FRAME_COUNT; frame++) {
        final int color = frame % STAGING_BUFFER_COUNT == 0 ? RED_ARGB : BLUE_ARGB;
        final boolean uploaded = stageAndUpload(recording, SMALL_WIDTH, SMALL_HEIGHT, color);
        assertTrue(uploaded);

        final int texture = recording.getTextureId();
        final byte[] pixels = readTexture(texture, SMALL_WIDTH, SMALL_HEIGHT);
        final byte[] expected = frame % STAGING_BUFFER_COUNT == 0 ? RED_RGB : BLUE_RGB;
        assertAllPixels(pixels, expected);
      }

      final int count = transferred.size();
      assertEquals(FRAME_COUNT, count);

      final ByteBuffer first = transferred.get(0);
      final ByteBuffer second = transferred.get(1);
      final ByteBuffer third = transferred.get(2);
      final ByteBuffer fourth = transferred.get(3);
      assertNotSame(first, second, "the player must not overwrite the buffer being uploaded");
      assertSame(first, third, "fixed-size playback must reuse its direct buffers");
      assertSame(second, fourth, "both staging buffers must be reusable");
    } finally {
      recording.release();
    }
  }

  private static GlTextureFilter recordingFilter(final List<ByteBuffer> transferred) {
    return new GlTextureFilter() {
      @Override
      void transfer(final ByteBuffer pixels, final int width, final int height) {
        transferred.add(pixels);
        super.transfer(pixels, width, height);
      }
    };
  }

  private static boolean stageAndUpload(final GlTextureFilter filter, final int width, final int height, final int argb) {
    stage(filter, width, height, argb);
    return filter.upload();
  }

  private static void stage(final GlTextureFilter filter, final int width, final int height, final int argb) {
    try (final ImageBuffer image = frame(width, height, argb)) {
      apply(filter, image);
    }
  }

  private static ImageBuffer frame(final int width, final int height, final int argb) {
    final int[] pixels = new int[width * height];
    Arrays.fill(pixels, argb);
    return ImageBuffer.buffer(pixels, width, height);
  }

  private static void apply(final GlTextureFilter filter, final ImageBuffer image) {
    final int width = image.getWidth();
    final int height = image.getHeight();
    final OriginalVideoMetadata metadata = OriginalVideoMetadata.of(width, height);
    final boolean kept = filter.applyFilter(image, metadata);
    assertFalse(kept, "the filter only reads the sample, so it reports no change");
  }

  private static byte[] readTexture(final int texture, final int width, final int height) {
    final ByteBuffer pixels = MemoryUtil.memAlloc(width * height * CHANNELS);
    try {
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
      GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, PACKED_ALIGNMENT);
      GL11.glGetTexImage(GL11.GL_TEXTURE_2D, BASE_LEVEL, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, pixels);
      GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, DEFAULT_ALIGNMENT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, NO_TEXTURE);

      final byte[] bytes = new byte[width * height * CHANNELS];
      pixels.get(bytes);

      return bytes;
    } finally {
      MemoryUtil.memFree(pixels);
    }
  }

  private static void assertAllPixels(final byte[] pixels, final byte[] rgb) {
    final int count = pixels.length / CHANNELS;
    for (int pixel = 0; pixel < count; pixel++) {
      final int pixelOffset = pixel * CHANNELS;
      final byte[] actual = { pixels[pixelOffset + RED_OFFSET], pixels[pixelOffset + GREEN_OFFSET], pixels[pixelOffset + BLUE_OFFSET] };
      assertArrayEquals(rgb, actual, "pixel %s has the uploaded color".formatted(pixel));
    }
  }

  @Test
  void shouldUploadTheNewestFrameWithItsColors() {
    this.filter.start();

    final boolean nothingYet = this.filter.upload();
    assertFalse(nothingYet);

    stage(this.filter, SMALL_WIDTH, SMALL_HEIGHT, RED_ARGB);
    stage(this.filter, SMALL_WIDTH, SMALL_HEIGHT, BLUE_ARGB);

    final boolean pending = this.filter.hasPendingFrame();
    assertTrue(pending);

    final boolean uploaded = this.filter.upload();
    final boolean pendingAfter = this.filter.hasPendingFrame();
    assertTrue(uploaded);
    assertFalse(pendingAfter);

    final boolean uploadedAgain = this.filter.upload();
    assertFalse(uploadedAgain);

    final int texture = this.filter.getTextureId();
    final int width = this.filter.getWidth();
    final int height = this.filter.getHeight();
    final byte[] pixels = readTexture(texture, SMALL_WIDTH, SMALL_HEIGHT);
    assertEquals(SMALL_WIDTH, width);
    assertEquals(SMALL_HEIGHT, height);
    assertAllPixels(pixels, BLUE_RGB);
  }

  @Test
  void shouldUpdateFramesOfTheSameSizeInPlaceAndResizeForNewSizes() {
    this.filter.start();
    stageAndUpload(this.filter, INITIAL_WIDTH, INITIAL_HEIGHT, GREEN_ARGB);
    stageAndUpload(this.filter, INITIAL_WIDTH, INITIAL_HEIGHT, WHITE_ARGB);

    final int texture = this.filter.getTextureId();
    final byte[] white = readTexture(texture, INITIAL_WIDTH, INITIAL_HEIGHT);
    assertAllPixels(white, WHITE_RGB);

    stageAndUpload(this.filter, RESIZED_WIDTH, RESIZED_HEIGHT, BLACK_ARGB);

    final int width = this.filter.getWidth();
    final int height = this.filter.getHeight();
    final byte[] black = readTexture(texture, RESIZED_WIDTH, RESIZED_HEIGHT);
    final int textureWidth = levelParameter(texture, GL11.GL_TEXTURE_WIDTH);
    assertAllPixels(black, BLACK_RGB);
    assertEquals(RESIZED_WIDTH, width);
    assertEquals(RESIZED_HEIGHT, height);
    assertEquals(RESIZED_WIDTH, textureWidth);
  }

  private static int levelParameter(final int texture, final int name) {
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
    final int value = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, BASE_LEVEL, name);
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, NO_TEXTURE);
    return value;
  }

  @Test
  void shouldResizeTheTextureWhenOnlyTheHeightChanges() {
    this.filter.start();
    stageAndUpload(this.filter, INITIAL_WIDTH, INITIAL_HEIGHT, GREEN_ARGB);

    final boolean uploaded = stageAndUpload(this.filter, INITIAL_WIDTH, TALLER_HEIGHT, RED_ARGB);
    assertTrue(uploaded);

    final int texture = this.filter.getTextureId();
    final int height = this.filter.getHeight();
    final int textureHeight = levelParameter(texture, GL11.GL_TEXTURE_HEIGHT);
    final byte[] pixels = readTexture(texture, INITIAL_WIDTH, TALLER_HEIGHT);
    assertEquals(TALLER_HEIGHT, height);
    assertEquals(TALLER_HEIGHT, textureHeight);
    assertAllPixels(pixels, RED_RGB);
  }

  @Test
  void shouldResizeTheTextureWhenOnlyTheWidthChanges() {
    this.filter.start();
    stageAndUpload(this.filter, INITIAL_WIDTH, INITIAL_HEIGHT, GREEN_ARGB);

    final boolean uploaded = stageAndUpload(this.filter, RESIZED_WIDTH, INITIAL_HEIGHT, BLUE_ARGB);
    assertTrue(uploaded);

    final int texture = this.filter.getTextureId();
    final int width = this.filter.getWidth();
    final int textureWidth = levelParameter(texture, GL11.GL_TEXTURE_WIDTH);
    final byte[] pixels = readTexture(texture, RESIZED_WIDTH, INITIAL_HEIGHT);
    assertEquals(RESIZED_WIDTH, width);
    assertEquals(RESIZED_WIDTH, textureWidth);
    assertAllPixels(pixels, BLUE_RGB);
  }

  @Test
  void shouldLeaveTheOpenGlStateOfTheCallerAsItWas() {
    final int callerTexture = GL11.glGenTextures();
    this.filter.start();
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, callerTexture);
    setUnpackState(CALLER_ALIGNMENT, CALLER_ROW_LENGTH, CALLER_SKIP_ROWS, CALLER_SKIP_PIXELS);
    stageAndUpload(this.filter, SMALL_WIDTH, SMALL_HEIGHT, GREEN_ARGB);

    final int boundAfterUpload = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    final int[] unpackState = readUnpackState();
    setUnpackState(DEFAULT_ALIGNMENT, DEFAULT_ROW_LENGTH, DEFAULT_SKIP, DEFAULT_SKIP);

    final int texture = this.filter.getTextureId();
    final byte[] pixels = readTexture(texture, SMALL_WIDTH, SMALL_HEIGHT);

    GL11.glBindTexture(GL11.GL_TEXTURE_2D, callerTexture);
    this.filter.start();

    final int boundAfterStart = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, NO_TEXTURE);

    final int[] callerUnpackState = { CALLER_ALIGNMENT, CALLER_ROW_LENGTH, CALLER_SKIP_ROWS, CALLER_SKIP_PIXELS };
    assertEquals(callerTexture, boundAfterUpload);
    assertArrayEquals(callerUnpackState, unpackState);
    assertEquals(callerTexture, boundAfterStart);
    assertAllPixels(pixels, GREEN_RGB);

    GL11.glDeleteTextures(callerTexture);
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

  @Test
  void shouldAcceptTheNextFrameWhileAFrameIsUploaded() {
    final CountDownLatch uploading = new CountDownLatch(1);
    final CountDownLatch finishUpload = new CountDownLatch(1);
    final CountDownLatch applied = new CountDownLatch(1);
    final GlTextureFilter slow = slowFilter(uploading, finishUpload);
    slow.start();
    stage(slow, SQUARE_SIZE, SQUARE_SIZE, RED_ARGB);
    this.startThread(() -> stageOnceUploading(slow, uploading, applied));
    this.startThread(() -> finishUploadOnceApplied(applied, finishUpload));

    final long before = System.nanoTime();
    final boolean firstUploaded = slow.upload();
    final long elapsedNanoseconds = System.nanoTime() - before;
    final long elapsedMilliseconds = TimeUnit.NANOSECONDS.toMillis(elapsedNanoseconds);
    final boolean appliedInTime = await(applied, LATCH_TIMEOUT_SECONDS);
    final boolean pending = slow.hasPendingFrame();
    final boolean secondUploaded = slow.upload();
    final int texture = slow.getTextureId();
    final byte[] pixels = readTexture(texture, SQUARE_SIZE, SQUARE_SIZE);
    slow.release();

    final boolean uploadFinishedInTime = elapsedMilliseconds < UPLOAD_LIMIT_MILLISECONDS;
    assertTrue(firstUploaded);
    assertTrue(appliedInTime);
    assertTrue(uploadFinishedInTime, () -> "the upload blocked the player for %s milliseconds".formatted(elapsedMilliseconds));
    assertTrue(pending);
    assertTrue(secondUploaded);
    assertAllPixels(pixels, BLUE_RGB);
  }

  private static GlTextureFilter slowFilter(final CountDownLatch uploading, final CountDownLatch finishUpload) {
    return new GlTextureFilter() {
      @Override
      void transfer(final ByteBuffer pixels, final int width, final int height) {
        uploading.countDown();

        final boolean finished = await(finishUpload, LATCH_TIMEOUT_SECONDS);
        assertTrue(finished, "the upload was never allowed to finish");

        super.transfer(pixels, width, height);
      }
    };
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

  private Thread startThread(final Runnable action) {
    final Thread thread = new Thread(action);
    thread.setUncaughtExceptionHandler((_, failure) -> this.workerFailures.add(failure));
    this.workers.add(thread);
    thread.start();

    return thread;
  }

  private static void stageOnceUploading(final GlTextureFilter filter, final CountDownLatch uploading, final CountDownLatch applied) {
    final boolean uploadStarted = await(uploading, LATCH_TIMEOUT_SECONDS);
    if (uploadStarted) {
      stage(filter, SQUARE_SIZE, SQUARE_SIZE, BLUE_ARGB);
      applied.countDown();
    }
  }

  private static void finishUploadOnceApplied(final CountDownLatch applied, final CountDownLatch finishUpload) {
    await(applied, APPLIED_TIMEOUT_SECONDS);
    finishUpload.countDown();
  }

  @Test
  void shouldRefuseOpenGlCallsWithoutACurrentContext() throws InterruptedException {
    final int borrowedTexture = GL11.glGenTextures();
    final GlTextureFilter borrowing = new GlTextureFilter(borrowedTexture);
    this.filter.start();

    final Throwable start = failureOnAnotherThread(borrowing::start);
    final Throwable upload = failureOnAnotherThread(borrowing::upload);
    final Throwable release = failureOnAnotherThread(this.filter::release);
    for (final Throwable failure : List.of(start, upload, release)) {
      assertInstanceOf(IllegalStateException.class, failure);
      final String message = failure.getMessage();
      final boolean explains = message.startsWith(CONTEXT_FAILURE_PREFIX);
      assertTrue(explains, message);
    }

    final int texture = this.filter.getTextureId();
    assertNotEquals(NO_TEXTURE, texture, "a failed release keeps the texture so it can be released on the render thread");

    GL11.glDeleteTextures(borrowedTexture);
  }

  private static Throwable failureOnAnotherThread(final Runnable action) throws InterruptedException {
    final List<Throwable> failures = new CopyOnWriteArrayList<>();
    final Thread other = new Thread(() -> runWithMockedOpenGl(action, failures));
    other.start();
    other.join(JOIN_TIMEOUT_MILLISECONDS);

    final int failureCount = failures.size();
    assertEquals(1, failureCount, failures::toString);

    return failures.getFirst();
  }

  private static void runWithMockedOpenGl(final Runnable action, final List<Throwable> failures) {
    // A GL call without a context fails the test here instead of crashing the native driver.
    // The mock covers only this thread; the render thread keeps real GL.
    try (final MockedStatic<GL11> calls = mockStatic(GL11.class)) {
      try {
        action.run();
      } catch (final RuntimeException actionException) {
        failures.add(actionException);
      }

      calls.verifyNoInteractions();
    } catch (final AssertionError exception) {
      failures.add(exception);
    }
  }

  @Test
  void shouldIgnoreFramesWithTooLittleData() {
    this.filter.start();
    final ImageBuffer truncated = mock(ImageBuffer.class);

    final Stubber widthStubbing = doReturn(TRUNCATED_SIZE);
    final ImageBuffer stubbedWidth = widthStubbing.when(truncated);
    stubbedWidth.getWidth();

    final Stubber heightStubbing = doReturn(TRUNCATED_SIZE);
    final ImageBuffer stubbedHeight = heightStubbing.when(truncated);
    stubbedHeight.getHeight();

    final int truncatedLength = TRUNCATED_SIZE * TRUNCATED_SIZE * CHANNELS - 1;
    final ByteBuffer data = ByteBuffer.allocateDirect(truncatedLength);
    final Stubber dataStubbing = doReturn(data);
    final ImageBuffer stubbedData = dataStubbing.when(truncated);
    stubbedData.getData();

    apply(this.filter, truncated);

    final boolean pending = this.filter.hasPendingFrame();
    assertFalse(pending);
  }

  @Test
  void shouldAcceptFramesFromAnotherThread() throws InterruptedException {
    this.filter.start();
    final Thread player = this.startThread(() -> stage(this.filter, SQUARE_SIZE, SQUARE_SIZE, BLUE_ARGB));
    Await.until("the frame of the player thread is staged", this.filter::hasPendingFrame);
    player.join(JOIN_TIMEOUT_MILLISECONDS);

    final boolean uploaded = this.filter.upload();
    assertTrue(uploaded);
  }

  @Test
  void shouldRefuseToUploadBeforeStartAndAfterRelease() {
    stage(this.filter, SQUARE_SIZE, SQUARE_SIZE, BLUE_ARGB);
    assertThrows(IllegalStateException.class, this.filter::upload);

    this.filter.start();
    this.filter.release();

    final int texture = this.filter.getTextureId();
    final boolean pending = this.filter.hasPendingFrame();
    assertEquals(NO_TEXTURE, texture);
    assertFalse(pending);
    assertThrows(IllegalStateException.class, this.filter::upload);
  }

  @Test
  void shouldDeleteOnlyTexturesItCreated() {
    this.filter.start();
    final int created = this.filter.getTextureId();
    this.filter.release();

    final boolean createdExists = GL11.glIsTexture(created);
    assertFalse(createdExists);

    final int existing = GL11.glGenTextures();
    final GlTextureFilter borrowing = new GlTextureFilter(existing);
    borrowing.start();

    final int borrowed = borrowing.getTextureId();
    assertEquals(existing, borrowed);

    final boolean uploaded = stageAndUpload(borrowing, SQUARE_SIZE, SQUARE_SIZE, GREEN_ARGB);
    assertTrue(uploaded);

    borrowing.release();
    borrowing.release();

    final boolean existingKept = GL11.glIsTexture(existing);
    final int afterRelease = borrowing.getTextureId();
    assertTrue(existingKept);
    assertEquals(existing, afterRelease);

    GL11.glDeleteTextures(existing);
  }

  @Test
  void shouldKeepItsTextureAndDimensionsWhenStartedAgain() {
    this.filter.start();
    final int firstTexture = this.filter.getTextureId();
    stageAndUpload(this.filter, PORTRAIT_WIDTH, PORTRAIT_HEIGHT, GREEN_ARGB);
    this.filter.start();

    final int secondTexture = this.filter.getTextureId();
    final int width = this.filter.getWidth();
    final int height = this.filter.getHeight();
    final byte[] pixels = readTexture(secondTexture, PORTRAIT_WIDTH, PORTRAIT_HEIGHT);
    assertEquals(firstTexture, secondTexture);
    assertEquals(PORTRAIT_WIDTH, width);
    assertEquals(PORTRAIT_HEIGHT, height);
    assertAllPixels(pixels, GREEN_RGB);
  }

  @Test
  void shouldAllocateStorageAgainAfterAnOwnedTextureIsRecreated() {
    this.filter.start();
    stageAndUpload(this.filter, PORTRAIT_WIDTH, PORTRAIT_HEIGHT, GREEN_ARGB);
    this.filter.release();
    this.filter.start();

    final int widthBeforeUpload = this.filter.getWidth();
    final int heightBeforeUpload = this.filter.getHeight();
    assertEquals(0, widthBeforeUpload);
    assertEquals(0, heightBeforeUpload);

    final boolean uploaded = stageAndUpload(this.filter, PORTRAIT_WIDTH, PORTRAIT_HEIGHT, BLUE_ARGB);
    assertTrue(uploaded);

    final int texture = this.filter.getTextureId();
    final int storageWidth = levelParameter(texture, GL11.GL_TEXTURE_WIDTH);
    final int storageHeight = levelParameter(texture, GL11.GL_TEXTURE_HEIGHT);
    final byte[] pixels = readTexture(texture, PORTRAIT_WIDTH, PORTRAIT_HEIGHT);
    assertEquals(PORTRAIT_WIDTH, storageWidth);
    assertEquals(PORTRAIT_HEIGHT, storageHeight);
    assertAllPixels(pixels, BLUE_RGB);
  }

  @Test
  void shouldKeepBorrowedTextureDimensionsAcrossReleaseAndStart() {
    final int texture = GL11.glGenTextures();
    final GlTextureFilter borrowing = new GlTextureFilter(texture);
    try {
      borrowing.start();
      stageAndUpload(borrowing, PORTRAIT_WIDTH, PORTRAIT_HEIGHT, GREEN_ARGB);
      borrowing.start();

      final int widthAfterStart = borrowing.getWidth();
      final int heightAfterStart = borrowing.getHeight();
      assertEquals(PORTRAIT_WIDTH, widthAfterStart);
      assertEquals(PORTRAIT_HEIGHT, heightAfterStart);

      borrowing.release();
      borrowing.start();

      final int widthAfterRelease = borrowing.getWidth();
      final int heightAfterRelease = borrowing.getHeight();
      final byte[] pixels = readTexture(texture, PORTRAIT_WIDTH, PORTRAIT_HEIGHT);
      assertEquals(PORTRAIT_WIDTH, widthAfterRelease);
      assertEquals(PORTRAIT_HEIGHT, heightAfterRelease);
      assertAllPixels(pixels, GREEN_RGB);
    } finally {
      borrowing.release();
      GL11.glDeleteTextures(texture);
    }
  }

  @Test
  void shouldRejectInvalidArguments() {
    assertThrows(IllegalArgumentException.class, () -> new GlTextureFilter(NO_TEXTURE));
    assertThrows(IllegalArgumentException.class, () -> new GlTextureFilter(NEGATIVE_TEXTURE_ID));

    final OriginalVideoMetadata metadata = OriginalVideoMetadata.of(SINGLE_PIXEL, SINGLE_PIXEL);
    assertThrows(NullPointerException.class, () -> this.filter.applyFilter(null, metadata));

    try (final ImageBuffer image = frame(SINGLE_PIXEL, SINGLE_PIXEL, BLACK_ARGB)) {
      assertThrows(NullPointerException.class, () -> this.filter.applyFilter(image, null));
    }
  }
}

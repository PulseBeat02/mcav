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
package me.brandonli.mcav.browser;

import java.awt.Rectangle;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.cef.browser.MCAVOffscreenBrowser;

// A browser engine without CEF for the helper and session tests: the path of the page picks what it does.
final class ScriptedEngine implements HelperEngine {

  private static final int START_EXIT_CODE = 3;

  private static final int NAME_NOT_RESOLVED_CODE = -105;
  private static final String NAME_NOT_RESOLVED_TEXT = "ERR_NAME_NOT_RESOLVED";

  private static final String ENGINE_VERSION = "scripted";

  private static final int HIGH_BYTE_SHIFT = 8;

  private static final int OPAQUE_ALPHA = 255;

  private static final String BINDING_EVENT_TEMPLATE = "{\"name\":\"%s\",\"payload\":\"%s\",\"executionContextId\":1}";

  static final String THROW_PATH = "/throw";
  static final String THROW_AFTER_OUTPUT_PATH = "/throw-after-output";
  static final String UNLINKED_PATH = "/unlinked";
  static final String EXIT_PATH = "/exit";
  static final String NEVER_PATH = "/never";
  static final String LOAD_ERROR_PATH = "/load-error";
  static final String SOUND_PATH = "/sound";
  static final String SOUND_ON_INPUT_PATH = "/sound-on-input";
  static final String FAIL_PATH = "/fail";

  static final int RED = 0x20;
  static final int GREEN = 0x40;

  static final int SOUND_CHUNKS = 3;
  static final int SOUND_FRAMES = 1024;
  private static final int SOUND_FRAME_BYTES = 4;

  static final String OUTPUT_BEFORE_FAILURE = "the scripted helper is about to fail";

  private static final long OUTPUT_READ_MILLISECONDS = 500L;
  private static final long CRASH_DELAY_MILLISECONDS = 1_000L;

  private final List<String> calls;

  private MCAVOffscreenBrowser.PaintListener painter = new NoPainter();
  private HelperEvents events = new NoEvents();
  private int width;
  private int height;
  private boolean stopped;
  private boolean soundOnInput;

  ScriptedEngine() {
    this.calls = new ArrayList<>();
  }

  public static void main(final String[] args) {
    final BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    final Runtime runtime = Runtime.getRuntime();
    final int status = BrowserHelper.runFromInput(input, new ScriptedEngine(), runtime::halt);
    System.exit(status);
  }

  @Override
  public void start(final HelperConfiguration configuration, final MCAVOffscreenBrowser.PaintListener painter, final HelperEvents events)
    throws Exception {
    this.painter = painter;
    this.events = events;
    this.width = configuration.getWidth();
    this.height = configuration.getHeight();
    final URI url = configuration.getUrl();
    final String path = url.getPath();
    switch (path) {
      case THROW_PATH -> {
        final String message = "The scripted start failed!";
        throw new IllegalStateException(message);
      }
      case THROW_AFTER_OUTPUT_PATH -> this.failAfterOutput();
      case UNLINKED_PATH -> {
        final String message = "The scripted native library is missing!";
        throw new UnsatisfiedLinkError(message);
      }
      case EXIT_PATH -> System.exit(START_EXIT_CODE);
      case NEVER_PATH -> {
      }
      case LOAD_ERROR_PATH -> {
        events.onReady(ENGINE_VERSION);
        events.onLoading(true);
        events.onLoadError(NAME_NOT_RESOLVED_CODE, NAME_NOT_RESOLVED_TEXT, url.toString());
        events.onLoading(false);
      }
      case SOUND_PATH -> this.playSound(events);
      case SOUND_ON_INPUT_PATH -> {
        this.soundOnInput = true;
        this.show(0);
        events.onAudio(chunk(0));
      }
      case FAIL_PATH -> this.crashRendererLater(events);
      default -> this.show(0);
    }
  }

  // the pause gives the server time to read the line
  private void failAfterOutput() throws InterruptedException {
    System.out.println(OUTPUT_BEFORE_FAILURE);
    System.out.flush();
    Thread.sleep(OUTPUT_READ_MILLISECONDS);
    final String message = "The scripted start failed after output!";
    throw new IllegalStateException(message);
  }

  private void playSound(final HelperEvents events) {
    this.show(0);
    final PageAudio audio = new PageAudio(events::onAudio, System::nanoTime);
    for (int chunk = 1; chunk <= SOUND_CHUNKS; chunk++) {
      final ByteBuffer samples = ByteBuffer.allocate(SOUND_FRAMES * SOUND_FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN);
      while (samples.hasRemaining()) {
        samples.putShort((short) chunk);
      }
      final String payload = Base64.getEncoder().encodeToString(samples.array());
      audio.onEvent(PageAudio.BINDING_EVENT, BINDING_EVENT_TEMPLATE.formatted("other", payload));
      audio.onEvent(PageAudio.BINDING_EVENT, BINDING_EVENT_TEMPLATE.formatted(PageAudio.BINDING, payload));
    }
  }

  private void crashRendererLater(final HelperEvents events) {
    this.show(0);
    final Thread later = new Thread(() -> this.pauseAndCrashRenderer(events));
    later.start();
  }

  private void pauseAndCrashRenderer(final HelperEvents events) {
    this.pause(CRASH_DELAY_MILLISECONDS);
    events.onFailure("scripted renderer crash");
  }

  private void pause(final long milliseconds) {
    try {
      Thread.sleep(milliseconds);
    } catch (final InterruptedException exception) {
      final Thread current = Thread.currentThread();
      current.interrupt();

      final String message = "The scripted pause was interrupted!";
      throw new IllegalStateException(message, exception);
    }
  }

  private void show(final int blue) {
    this.events.onReady(ENGINE_VERSION);
    this.events.onLoading(true);
    this.paint(blue);
    this.events.onNotice("scripted notice");
    this.events.onLoading(false);
  }

  private synchronized void paint(final int value) {
    final ByteBuffer buffer = ByteBuffer.allocateDirect(this.width * this.height * HelperProtocol.PIXEL_BYTES);
    for (int index = 0; index < this.width * this.height; index++) {
      buffer.put((byte) value);
      buffer.put((byte) (GREEN ^ (value >>> HIGH_BYTE_SHIFT)));
      buffer.put((byte) RED);
      buffer.put((byte) OPAQUE_ALPHA);
    }

    buffer.flip();
    final Rectangle[] all = { new Rectangle(0, 0, this.width, this.height) };
    this.painter.onPaint(false, all, buffer, this.width, this.height);
  }

  @Override
  public synchronized void dispatch(final List<DevToolsInput.DevToolsCall> dispatched) {
    for (final DevToolsInput.DevToolsCall call : dispatched) {
      final String method = call.getMethod();
      final String parameters = call.getParameters();
      this.calls.add("%s %s".formatted(method, parameters));
    }

    this.paint(this.calls.size());

    if (this.soundOnInput) {
      this.events.onAudio(chunk(this.calls.size()));
    }
  }

  static byte[] chunk(final int value) {
    final ByteBuffer samples = ByteBuffer.allocate(SOUND_FRAMES * SOUND_FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    while (samples.hasRemaining()) {
      samples.putShort((short) value);
    }

    return samples.array();
  }

  @Override
  public synchronized void stop() {
    this.stopped = true;
  }

  synchronized List<String> getCalls() {
    return List.copyOf(this.calls);
  }

  synchronized boolean isStopped() {
    return this.stopped;
  }

  private static final class NoPainter implements MCAVOffscreenBrowser.PaintListener {

    @Override
    public void onPaint(
      final boolean isPopup,
      final Rectangle[] dirtyRectangles,
      final ByteBuffer buffer,
      final int width,
      final int height
    ) {}

    @Override
    public void onPopupShow(final boolean isShown) {}

    @Override
    public void onPopupSize(final Rectangle bounds) {}
  }

  private static final class NoEvents implements HelperEvents {

    @Override
    public void onReady(final String engineVersion) {}

    @Override
    public void onPageStart() {}

    @Override
    public void onLoading(final boolean loading) {}

    @Override
    public void onLoadError(final int code, final String text, final String url) {}

    @Override
    public void onNotice(final String text) {}

    @Override
    public void onFailure(final String text) {}

    @Override
    public void onAudio(final byte[] samples) {}
  }
}

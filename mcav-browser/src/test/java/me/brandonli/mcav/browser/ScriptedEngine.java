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
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.cef.browser.McavOffscreenBrowser;

/**
 * A browser engine without CEF for tests of the helper and its session. What it does depends on the path of the page:
 *
 * <ul>
 *   <li>{@code /page}: reports ready, loads, and paints the whole page in one color; every input it receives paints the
 *   page again with the blue channel counting the DevTools calls so far, so a test can see input arrive;</li>
 *   <li>{@code /load-error}: fails to load the page;</li>
 *   <li>{@code /fail}: shows the page, then, a second later, fails as a crashed renderer would;</li>
 *   <li>{@code /exit}: ends the helper process during the start;</li>
 *   <li>{@code /throw}: fails to start;</li>
 *   <li>{@code /never}: never reports anything.</li>
 * </ul>
 */
final class ScriptedEngine implements HelperEngine {

  static final int RED = 0x20;
  static final int GREEN = 0x40;

  private final List<String> calls;
  private McavOffscreenBrowser.PaintListener painter = new NoPainter();
  private HelperEvents events = new NoEvents();
  private int width;
  private int height;
  private boolean stopped;

  ScriptedEngine() {
    this.calls = new ArrayList<>();
  }

  /**
   * Starts the helper with this engine, as the real helper starts with CEF.
   *
   * @param args ignored
   */
  public static void main(final String[] args) {
    final java.io.BufferedReader input = new java.io.BufferedReader(
      new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8)
    );
    final int status = BrowserHelper.runFromInput(input, new ScriptedEngine());
    System.exit(status);
  }

  @Override
  public void start(final HelperConfiguration configuration, final McavOffscreenBrowser.PaintListener painter, final HelperEvents events)
    throws Exception {
    this.painter = painter;
    this.events = events;
    this.width = configuration.getWidth();
    this.height = configuration.getHeight();
    final URI url = configuration.getUrl();
    final String path = url.getPath();
    switch (path) {
      case "/throw" -> throw new IllegalStateException("scripted start failure");
      case "/exit" -> System.exit(3);
      case "/never" -> {
        // nothing is ever reported
      }
      case "/load-error" -> {
        events.onReady("scripted");
        events.onLoading(true);
        events.onLoadError(-105, "ERR_NAME_NOT_RESOLVED", url.toString());
        events.onLoading(false);
      }
      case "/fail" -> {
        this.show(0);
        final Thread later = new Thread(() -> {
          pause(1_000L);
          events.onFailure("scripted renderer crash");
        });
        later.start();
      }
      default -> this.show(0);
    }
  }

  private static void pause(final long millis) {
    try {
      Thread.sleep(millis);
    } catch (final InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  private void show(final int blue) {
    this.events.onReady("scripted");
    this.events.onLoading(true);
    this.paint(blue);
    this.events.onNotice("scripted notice");
    this.events.onLoading(false);
  }

  private synchronized void paint(final int blue) {
    final ByteBuffer buffer = ByteBuffer.allocateDirect(this.width * this.height * 4);
    for (int index = 0; index < this.width * this.height; index++) {
      buffer.put((byte) blue);
      buffer.put((byte) GREEN);
      buffer.put((byte) RED);
      buffer.put((byte) 255);
    }
    buffer.flip();
    final Rectangle[] all = { new Rectangle(0, 0, this.width, this.height) };
    this.painter.onPaint(false, all, buffer, this.width, this.height);
  }

  @Override
  public synchronized void dispatch(final List<DevToolsInput.DevToolsCall> dispatched) {
    for (final DevToolsInput.DevToolsCall call : dispatched) {
      this.calls.add(call.getMethod() + " " + call.getParameters());
    }
    this.paint(this.calls.size() & 0xFF);
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

  private static final class NoPainter implements McavOffscreenBrowser.PaintListener {

    @Override
    public void onPaint(final boolean popup, final Rectangle[] dirtyRects, final ByteBuffer buffer, final int width, final int height) {}

    @Override
    public void onPopupShow(final boolean show) {}

    @Override
    public void onPopupSize(final Rectangle bounds) {}
  }

  private static final class NoEvents implements HelperEvents {

    @Override
    public void onReady(final String engineVersion) {}

    @Override
    public void onLoading(final boolean loading) {}

    @Override
    public void onLoadError(final int code, final String text, final String url) {}

    @Override
    public void onNotice(final String text) {}

    @Override
    public void onFailure(final String text) {}
  }
}

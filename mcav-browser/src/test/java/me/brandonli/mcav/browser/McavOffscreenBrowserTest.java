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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.awt.Point;
import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.cef.CefBrowserSettings;
import org.cef.CefClient;
import org.cef.browser.McavOffscreenBrowser;
import org.cef.callback.CefDragData;
import org.cef.handler.CefScreenInfo;
import org.junit.jupiter.api.Test;

/**
 * Tests the off-screen browser without CEF: its native methods are not loaded, which JCEF reports and ignores, so the
 * Java side of every method can run here.
 */
class McavOffscreenBrowserTest {

  private final RecordingPainter painter = new RecordingPainter();
  private final McavOffscreenBrowser browser = new McavOffscreenBrowser(
    mock(CefClient.class),
    "https://example.com/",
    640,
    480,
    this.painter,
    new CefBrowserSettings()
  );

  @Test
  void theViewIsThePageAtTheOrigin() {
    final Rectangle view = this.browser.getViewRect(this.browser);
    assertEquals(new Rectangle(0, 0, 640, 480), view);
    view.width = 1;
    assertEquals(640, this.browser.getViewRect(this.browser).width, "a copy is handed out");
  }

  @Test
  void theScreenIsLeftToCefBecauseJcefDeletesAReferenceTwiceOtherwise() {
    final CefScreenInfo info = new CefScreenInfo();
    assertFalse(this.browser.getScreenInfo(this.browser, info));
  }

  @Test
  void aPointOfThePageIsTheSamePointOfTheScreen() {
    final Point point = new Point(3, 4);
    final Point screen = this.browser.getScreenPoint(this.browser, point);
    assertEquals(point, screen);
    assertNotSame(point, screen);
  }

  @Test
  void paintsAndPopupsGoToTheListener() {
    final ByteBuffer buffer = ByteBuffer.allocate(4);
    final Rectangle[] dirty = { new Rectangle(0, 0, 1, 1) };
    this.browser.onPaint(this.browser, false, dirty, buffer, 1, 1);
    this.browser.onPaint(this.browser, true, dirty, buffer, 1, 1);
    this.browser.onPopupShow(this.browser, true);
    this.browser.onPopupSize(this.browser, new Rectangle(1, 2, 3, 4));
    assertEquals(
      List.of("paint false 1x1", "paint true 1x1", "show true", "size java.awt.Rectangle[x=1,y=2,width=3,height=4]"),
      this.painter.log
    );
  }

  @Test
  void theBrowserRendersItselfAndHasADetachedComponent() {
    assertSame(this.browser, this.browser.getRenderHandler());
    final java.awt.Component component = this.browser.getUIComponent();
    assertNotNull(component);
    assertNull(component.getParent());
    assertFalse(component.isDisplayable());
  }

  @Test
  void focusChangesReachCefOnlyWhenTheyChangeSomething() {
    assertFalse(this.browser.hasFocus());
    this.browser.setFocus(true);
    assertTrue(this.browser.hasFocus());
    // JCEF gives a focused browser focus again from inside the focus event; that call must change nothing
    this.browser.setFocus(true);
    assertTrue(this.browser.hasFocus());
    this.browser.setFocus(false);
    assertFalse(this.browser.hasFocus());
    this.browser.setFocus(false);
    assertFalse(this.browser.hasFocus());
  }

  @Test
  void developerToolsScreenshotsAndPaintListenersAreRefused() {
    assertThrows(UnsupportedOperationException.class, this.browser::openDevTools);
    final ExecutionException screenshot = assertThrows(ExecutionException.class, () -> this.browser.createScreenshot(true).get());
    assertInstanceOf(UnsupportedOperationException.class, screenshot.getCause());
    assertThrows(UnsupportedOperationException.class, () -> this.browser.addOnPaintListener(event -> {}));
    assertThrows(UnsupportedOperationException.class, () -> this.browser.setOnPaintListener(event -> {}));
    this.browser.removeOnPaintListener(event -> {});
  }

  @Test
  void theCursorAndDragAndDropAreIgnored() {
    assertTrue(this.browser.onCursorChange(this.browser, 3));
    assertFalse(this.browser.startDragging(this.browser, mock(CefDragData.class), 1, 2, 3));
    this.browser.updateDragCursor(this.browser, 1);
    assertTrue(this.painter.log.isEmpty());
  }

  @Test
  void creatingTheBrowserWithoutCefLeavesItUncreated() {
    // without the natives JCEF reports the missing native method and returns; nothing is painted
    this.browser.createImmediately();
    assertTrue(this.painter.log.isEmpty());
  }

  /**
   * Records what the browser hands to its listener.
   */
  static final class RecordingPainter implements McavOffscreenBrowser.PaintListener {

    final List<String> log = new ArrayList<>();

    @Override
    public void onPaint(final boolean popup, final Rectangle[] dirtyRects, final ByteBuffer buffer, final int width, final int height) {
      this.log.add("paint " + popup + " " + width + "x" + height);
    }

    @Override
    public void onPopupShow(final boolean show) {
      this.log.add("show " + show);
    }

    @Override
    public void onPopupSize(final Rectangle bounds) {
      this.log.add("size " + bounds);
    }
  }
}

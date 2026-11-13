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

import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import me.brandonli.mcav.browser.testing.StandardError;
import org.cef.CefBrowserSettings;
import org.cef.CefClient;
import org.cef.browser.MCAVOffscreenBrowser;
import org.cef.callback.CefDragData;
import org.cef.handler.CefRenderHandler;
import org.cef.handler.CefScreenInfo;
import org.junit.jupiter.api.Test;

// Without the natives, JCEF reports and ignores every native call, so the Java side of each method runs here.
class MCAVOffscreenBrowserTest {

  static final Pattern CREATION_REPORT = Pattern.compile("^java\\.lang\\.UnsatisfiedLinkError: .*\\.N_CreateBrowser\\(", Pattern.MULTILINE);
  static final Pattern FOCUS_REPORT = Pattern.compile("^java\\.lang\\.UnsatisfiedLinkError: .*\\.N_SetFocus\\(", Pattern.MULTILINE);

  private static final Pattern REPAINT_REPORT = Pattern.compile(
    "^java\\.lang\\.UnsatisfiedLinkError: .*\\.N_Invalidate\\(",
    Pattern.MULTILINE
  );

  private static final int VIEW_WIDTH = 640;
  private static final int VIEW_HEIGHT = 480;

  private final RecordingPainter painter = new RecordingPainter();
  private final MCAVOffscreenBrowser browser = new MCAVOffscreenBrowser(
    mock(CefClient.class),
    "https://example.com/",
    VIEW_WIDTH,
    VIEW_HEIGHT,
    this.painter,
    new CefBrowserSettings()
  );

  @Test
  void shouldPlaceTheViewAtTheOriginOfThePage() {
    final Rectangle view = this.browser.getViewRect(this.browser);
    final Rectangle expectedView = new Rectangle(0, 0, VIEW_WIDTH, VIEW_HEIGHT);
    assertEquals(expectedView, view);

    view.width = 1;
    final Rectangle freshView = this.browser.getViewRect(this.browser);
    assertEquals(VIEW_WIDTH, freshView.width, "a copy is handed out");
  }

  @Test
  void shouldLeaveTheScreenToCefBecauseJcefDeletesAReferenceTwiceOtherwise() {
    final CefScreenInfo information = new CefScreenInfo();
    final boolean providesScreenInformation = this.browser.getScreenInfo(this.browser, information);
    assertFalse(providesScreenInformation);
  }

  @Test
  void shouldMapAPointOfThePageToTheSamePointOfTheScreen() {
    final Point point = new Point(3, 4);
    final Point screen = this.browser.getScreenPoint(this.browser, point);
    assertEquals(point, screen);
    assertNotSame(point, screen);
  }

  @Test
  void shouldSendPaintsAndPopupsToTheListener() {
    final ByteBuffer buffer = ByteBuffer.allocate(4);
    final Rectangle[] dirty = { new Rectangle(0, 0, 1, 1) };
    this.browser.onPaint(this.browser, false, dirty, buffer, 1, 1);
    this.browser.onPaint(this.browser, true, dirty, buffer, 1, 1);
    this.browser.onPopupShow(this.browser, true);
    this.browser.onPopupSize(this.browser, new Rectangle(1, 2, 3, 4));
    final List<String> expectedPainterEvents = List.of(
      "paint false 1x1",
      "paint true 1x1",
      "show true",
      "size java.awt.Rectangle[x=1,y=2,width=3,height=4]"
    );
    assertEquals(expectedPainterEvents, this.painter.log);
  }

  @Test
  void shouldRenderItselfAndHaveADetachedComponent() {
    final CefRenderHandler renderHandler = this.browser.getRenderHandler();
    assertSame(this.browser, renderHandler);

    final Component component = this.browser.getUIComponent();
    assertNotNull(component);

    final Container parent = component.getParent();
    assertNull(parent);

    final boolean displayable = component.isDisplayable();
    assertFalse(displayable);
  }

  @Test
  void shouldPassFocusChangesToCefOnlyWhenTheyChangeSomething() {
    try (final StandardError errors = new StandardError()) {
      final boolean focusedAtFirst = this.browser.isFocused();
      assertFalse(focusedAtFirst);

      this.browser.setFocus(true);
      final boolean focusedAfterFocusing = this.browser.isFocused();
      final long reportsAfterFocusing = this.countReports(errors, FOCUS_REPORT);
      assertTrue(focusedAfterFocusing);
      assertEquals(1, reportsAfterFocusing);

      // JCEF gives a focused browser focus again from inside the focus event; that call must change nothing
      this.browser.setFocus(true);
      final boolean focusedAfterRefocusing = this.browser.isFocused();
      final long reportsAfterRefocusing = this.countReports(errors, FOCUS_REPORT);
      assertTrue(focusedAfterRefocusing);
      assertEquals(1, reportsAfterRefocusing);

      this.browser.setFocus(false);
      final boolean focusedAfterUnfocusing = this.browser.isFocused();
      final long reportsAfterUnfocusing = this.countReports(errors, FOCUS_REPORT);
      assertFalse(focusedAfterUnfocusing);
      assertEquals(2, reportsAfterUnfocusing);

      this.browser.setFocus(false);
      final boolean focusedAfterUnfocusingAgain = this.browser.isFocused();
      final long reportsAfterUnfocusingAgain = this.countReports(errors, FOCUS_REPORT);
      assertFalse(focusedAfterUnfocusingAgain);
      assertEquals(2, reportsAfterUnfocusingAgain);
    }
  }

  @Test
  void shouldRefuseDeveloperToolsScreenshotsAndPaintListeners() {
    assertThrows(UnsupportedOperationException.class, this.browser::openDevTools);

    final ExecutionException screenshot = assertThrows(ExecutionException.class, () -> this.browser.createScreenshot(true).get());
    final Throwable cause = screenshot.getCause();
    assertInstanceOf(UnsupportedOperationException.class, cause);
    assertThrows(UnsupportedOperationException.class, () -> this.browser.addOnPaintListener(event -> {}));
    assertThrows(UnsupportedOperationException.class, () -> this.browser.setOnPaintListener(event -> {}));

    this.browser.removeOnPaintListener(event -> {});
  }

  @Test
  void shouldIgnoreTheCursorAndDragAndDrop() {
    final boolean cursorChangeHandled = this.browser.onCursorChange(this.browser, 3);
    assertTrue(cursorChangeHandled);

    final boolean draggingStarted = this.browser.startDragging(this.browser, mock(CefDragData.class), 1, 2, 3);
    assertFalse(draggingStarted);

    this.browser.updateDragCursor(this.browser, 1);
  }

  @Test
  void shouldAskCefToCreateTheBrowserWhenCreatedImmediately() {
    try (final StandardError errors = new StandardError()) {
      this.browser.createImmediately();
      final long creationReports = this.countReports(errors, CREATION_REPORT);
      assertEquals(1, creationReports);
    }
  }

  @Test
  void shouldAskCefToPaintTheWholeViewAgainOnRepaint() {
    try (final StandardError errors = new StandardError()) {
      this.browser.repaint();
      final long repaintReports = this.countReports(errors, REPAINT_REPORT);
      assertEquals(1, repaintReports);
    }
  }

  private long countReports(final StandardError errors, final Pattern report) {
    final String text = errors.text();
    return report.matcher(text).results().count();
  }

  private static final class RecordingPainter implements MCAVOffscreenBrowser.PaintListener {

    private final List<String> log = new ArrayList<>();

    @Override
    public void onPaint(
      final boolean isPopup,
      final Rectangle[] dirtyRectangles,
      final ByteBuffer buffer,
      final int width,
      final int height
    ) {
      this.log.add("paint " + isPopup + " " + width + "x" + height);
    }

    @Override
    public void onPopupShow(final boolean isShown) {
      this.log.add("show " + isShown);
    }

    @Override
    public void onPopupSize(final Rectangle bounds) {
      this.log.add("size " + bounds);
    }
  }
}

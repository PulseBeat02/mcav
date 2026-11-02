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
package org.cef.browser;

import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.Serial;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.cef.CefBrowserSettings;
import org.cef.CefClient;
import org.cef.callback.CefDragData;
import org.cef.handler.CefRenderHandler;
import org.cef.handler.CefScreenInfo;

/**
 * An off-screen browser that hands every painted frame to a listener as the plain pixel buffer CEF paints into, with
 * no OpenGL canvas, no window and no JOGL class anywhere.
 *
 * <p>JCEF's own off-screen browser, {@code CefBrowserOsr}, draws every frame into a JOGL {@code GLCanvas}, which needs
 * a display and the JVM options JOGL requires. The base class of every JCEF browser is package-private, so this class
 * lives in JCEF's package; it must be loaded by the class loader that loaded JCEF, which is the case whenever both
 * are on one class path, as in the helper process of MCAV's browser.
 *
 * <p>Two JCEF defects are worked around here. {@link #getScreenInfo} returns false, because JCEF's native side deletes
 * a JNI reference twice when it returns true; CEF then takes the screen from {@link #getViewRect}. And
 * {@link #setFocus} ignores calls that do not change the focus, because {@link CefClient#onGotFocus} gives the browser
 * focus again from inside the focus event, which otherwise recurses through CEF until the stack overflows.
 *
 * <p>This class is thread-safe: the focus state is guarded by the browser's lock and the size of the page never
 * changes. JCEF calls the render handler methods on CEF's user-interface thread.
 *
 * @since 1.0.0
 */
public final class MCAVOffscreenBrowser extends CefBrowser_N implements CefRenderHandler {

  private static final Component DETACHED = new DetachedComponent();

  private static final String PAINT_LISTENER_MESSAGE = "The frames go to the paint listener of the browser!";

  private final Rectangle viewBounds;
  private final PaintListener listener;

  private boolean focused;

  /**
   * Creates a browser, which {@link #createImmediately()} then creates in CEF.
   *
   * @param client   the client the browser belongs to
   * @param url      the page to open
   * @param width    the width of the page in pixels
   * @param height   the height of the page in pixels
   * @param listener receives the painted frames
   * @param settings the settings of the browser, such as its frame rate
   * @since 1.0.0
   */
  public MCAVOffscreenBrowser(
    final CefClient client,
    final String url,
    final int width,
    final int height,
    final PaintListener listener,
    final CefBrowserSettings settings
  ) {
    super(client, url, null, null, null, settings);
    this.viewBounds = new Rectangle(0, 0, width, height);
    this.listener = listener;
  }

  /**
   * Creates the browser in CEF as a windowless browser with an opaque background.
   *
   * @since 1.0.0
   */
  @Override
  public void createImmediately() {
    final CefClient client = this.getClient();
    final String url = this.getUrl();
    final CefRequestContext context = this.getRequestContext();
    this.createBrowser(client, 0L, url, true, false, null, context);
  }

  /**
   * Refuses to open developer tools, which would need a window.
   *
   * @throws UnsupportedOperationException always
   * @since 1.0.0
   */
  @Override
  protected CefBrowser_N createDevToolsBrowser(
    final CefClient client,
    final String url,
    final CefRequestContext context,
    final CefBrowser_N parent,
    final Point inspectAt
  ) {
    final String message = "Developer tools are not available in an off-screen browser!";
    throw new UnsupportedOperationException(message);
  }

  /**
   * Refuses to take a screenshot; the painted frames already reach the listener.
   *
   * @param nativeResolution ignored
   * @return a future that failed with an {@link UnsupportedOperationException}
   * @since 1.0.0
   */
  @Override
  public CompletableFuture<BufferedImage> createScreenshot(final boolean nativeResolution) {
    final String message = "Screenshots are taken from the painted frames!";
    final UnsupportedOperationException failure = new UnsupportedOperationException(message);
    return CompletableFuture.failedFuture(failure);
  }

  /**
   * Asks CEF to paint the whole view again, which reaches the listeners as a new frame.
   *
   * @since 1.0.0
   */
  public void repaint() {
    this.invalidate();
  }

  /**
   * Gives the browser focus or takes it away, unless it already has that state.
   *
   * @param enable true to give the browser focus
   * @since 1.0.0
   */
  @Override
  public synchronized void setFocus(final boolean enable) {
    if (this.focused == enable) {
      return;
    }

    this.focused = enable;
    super.setFocus(enable);
  }

  /**
   * Returns the size of the page.
   *
   * @param browser the browser, which is this one
   * @return the size of the page at the origin
   * @since 1.0.0
   */
  @Override
  public Rectangle getViewRect(final CefBrowser browser) {
    return new Rectangle(this.viewBounds);
  }

  /**
   * Leaves the screen information to CEF, which takes it from {@link #getViewRect}.
   *
   * @param browser           the browser, which is this one
   * @param screenInformation the screen information, left unchanged
   * @return false
   * @since 1.0.0
   */
  @Override
  public boolean getScreenInfo(final CefBrowser browser, final CefScreenInfo screenInformation) {
    return false;
  }

  /**
   * Maps a point of the page to the screen, which the page covers exactly.
   *
   * @param browser   the browser, which is this one
   * @param viewPoint the point on the page
   * @return the same point
   * @since 1.0.0
   */
  @Override
  public Point getScreenPoint(final CefBrowser browser, final Point viewPoint) {
    return new Point(viewPoint);
  }

  /**
   * Tells the listener that a popup widget, such as the list of a drop-down box, was shown or hidden.
   *
   * @param browser the browser, which is this one
   * @param isShown true if it was shown
   * @since 1.0.0
   */
  @Override
  public void onPopupShow(final CefBrowser browser, final boolean isShown) {
    this.listener.onPopupShow(isShown);
  }

  /**
   * Tells the listener where a popup widget is drawn.
   *
   * @param browser the browser, which is this one
   * @param bounds  the bounds of the popup on the page
   * @since 1.0.0
   */
  @Override
  public void onPopupSize(final CefBrowser browser, final Rectangle bounds) {
    this.listener.onPopupSize(bounds);
  }

  /**
   * Hands a painted frame to the listener. The buffer belongs to CEF and is only valid during the call.
   *
   * @param browser         the browser, which is this one
   * @param isPopup         true if the buffer holds a popup widget rather than the page
   * @param dirtyRectangles the parts of the buffer that changed
   * @param buffer          the pixels, four bytes per pixel in BGRA order, row by row
   * @param width           the width of the buffer in pixels
   * @param height          the height of the buffer in pixels
   * @since 1.0.0
   */
  @Override
  public void onPaint(
    final CefBrowser browser,
    final boolean isPopup,
    final Rectangle[] dirtyRectangles,
    final ByteBuffer buffer,
    final int width,
    final int height
  ) {
    this.listener.onPaint(isPopup, dirtyRectangles, buffer, width, height);
  }

  /**
   * Refuses paint event listeners; the frames go to the listener given when the browser was constructed.
   *
   * @param listener ignored
   * @throws UnsupportedOperationException always
   * @since 1.0.0
   */
  @Override
  public void addOnPaintListener(final Consumer<CefPaintEvent> listener) {
    throw new UnsupportedOperationException(PAINT_LISTENER_MESSAGE);
  }

  /**
   * Does nothing, as no paint event listener can be added.
   *
   * @param listener ignored
   * @since 1.0.0
   */
  @Override
  public void removeOnPaintListener(final Consumer<CefPaintEvent> listener) {}

  /**
   * Ignores the cursor, which is not shown.
   *
   * @param browser    the browser, which is this one
   * @param cursorType the cursor CEF wants to show
   * @return true, so CEF does not try to show it itself
   * @since 1.0.0
   */
  @Override
  public boolean onCursorChange(final CefBrowser browser, final int cursorType) {
    return true;
  }

  /**
   * Refuses to start a drag and drop operation, which would need a window.
   *
   * @param browser  the browser, which is this one
   * @param dragData what would be dragged
   * @param mask     the allowed operations
   * @param pageX    the x coordinate where the drag started
   * @param pageY    the y coordinate where the drag started
   * @return false, so CEF cancels the drag
   * @since 1.0.0
   */
  @Override
  public boolean startDragging(final CefBrowser browser, final CefDragData dragData, final int mask, final int pageX, final int pageY) {
    return false;
  }

  /**
   * Ignores the drag cursor, as no drag is ever started.
   *
   * @param browser   the browser, which is this one
   * @param operation the current drag operation
   * @since 1.0.0
   */
  @Override
  public void updateDragCursor(final CefBrowser browser, final int operation) {}

  /**
   * Returns a component that is never shown. JCEF looks up the component of a browser in its focus and close handling,
   * and this one keeps those paths from failing on a browser that has no window.
   *
   * @return the component
   * @since 1.0.0
   */
  @Override
  public Component getUIComponent() {
    return DETACHED;
  }

  /**
   * Returns this browser, which renders its own frames.
   *
   * @return this browser
   * @since 1.0.0
   */
  @Override
  public CefRenderHandler getRenderHandler() {
    return this;
  }

  /**
   * Refuses paint event listeners; the frames go to the listener given when the browser was constructed.
   *
   * @param listener ignored
   * @throws UnsupportedOperationException always
   * @since 1.0.0
   */
  @Override
  public void setOnPaintListener(final Consumer<CefPaintEvent> listener) {
    throw new UnsupportedOperationException(PAINT_LISTENER_MESSAGE);
  }

  /**
   * Returns whether the browser is focused, as far as this browser told CEF.
   *
   * @return true if the browser has focus
   * @since 1.0.0
   */
  public synchronized boolean isFocused() {
    return this.focused;
  }

  /**
   * Receives the painted frames of a browser.
   *
   * <p>The methods are called on CEF's user-interface thread; an implementation that shares state with other threads
   * must guard it.
   *
   * @since 1.0.0
   */
  public interface PaintListener {
    /**
     * Receives a painted frame. The buffer belongs to CEF and is only valid during the call.
     *
     * @param isPopup         true if the buffer holds a popup widget rather than the page
     * @param dirtyRectangles the parts of the buffer that changed
     * @param buffer          the pixels, four bytes per pixel in BGRA order, row by row
     * @param width           the width of the buffer in pixels
     * @param height          the height of the buffer in pixels
     * @since 1.0.0
     */
    void onPaint(final boolean isPopup, final Rectangle[] dirtyRectangles, final ByteBuffer buffer, final int width, final int height);

    /**
     * Receives that a popup widget was shown or hidden.
     *
     * @param isShown true if it was shown
     * @since 1.0.0
     */
    void onPopupShow(final boolean isShown);

    /**
     * Receives where a popup widget is drawn.
     *
     * @param bounds the bounds of the popup on the page
     * @since 1.0.0
     */
    void onPopupSize(final Rectangle bounds);
  }

  private static final class DetachedComponent extends Component {

    @Serial
    private static final long serialVersionUID = 1L;
  }
}

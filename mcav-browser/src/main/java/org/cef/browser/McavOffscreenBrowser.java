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
 * are on one class path, as in the helper process of mcav's browser.
 *
 * <p>Two JCEF defects are worked around here. {@link #getScreenInfo} returns false, because JCEF's native side deletes
 * a JNI reference twice when it returns true; CEF then takes the screen from {@link #getViewRect}. And
 * {@link #setFocus} ignores calls that do not change the focus, because {@link CefClient#onGotFocus} gives the browser
 * focus again from inside the focus event, which otherwise recurses through CEF until the stack overflows.
 */
public final class McavOffscreenBrowser extends CefBrowser_N implements CefRenderHandler {

  private static final Component DETACHED = new DetachedComponent();

  private final Rectangle viewBounds;
  private final PaintListener listener;
  private boolean focused;

  /**
   * Constructs a browser. It is created in CEF by {@link #createImmediately()}.
   *
   * @param client   the client the browser belongs to
   * @param url      the page to open
   * @param width    the width of the page in pixels
   * @param height   the height of the page in pixels
   * @param listener receives the painted frames
   * @param settings the settings of the browser, such as its frame rate
   */
  public McavOffscreenBrowser(
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
   */
  @Override
  public void createImmediately() {
    final CefClient client = this.getClient();
    final String url = this.getUrl();
    final CefRequestContext context = this.getRequestContext();
    this.createBrowser(client, 0L, url, true, false, null, context);
  }

  /**
   * Gets a component that is never shown. JCEF looks up the component of a browser in its focus and close handling,
   * and this one keeps those paths from failing on a browser that has no window.
   *
   * @return the component
   */
  @Override
  public Component getUIComponent() {
    return DETACHED;
  }

  /**
   * Gets this browser, which renders its own frames.
   *
   * @return this browser
   */
  @Override
  public CefRenderHandler getRenderHandler() {
    return this;
  }

  /**
   * Refuses to open developer tools, which would need a window.
   *
   * @throws UnsupportedOperationException always
   */
  @Override
  protected CefBrowser_N createDevToolsBrowser(
    final CefClient client,
    final String url,
    final CefRequestContext context,
    final CefBrowser_N parent,
    final Point inspectAt
  ) {
    throw new UnsupportedOperationException("Developer tools are not available in an off-screen browser");
  }

  /**
   * Refuses to take a screenshot; the painted frames already reach the listener.
   *
   * @param nativeResolution ignored
   * @return a future that failed with an {@link UnsupportedOperationException}
   */
  @Override
  public CompletableFuture<BufferedImage> createScreenshot(final boolean nativeResolution) {
    final UnsupportedOperationException failure = new UnsupportedOperationException("Screenshots are taken from the painted frames");
    return CompletableFuture.failedFuture(failure);
  }

  /**
   * Gives the browser focus or takes it away, unless it already has that state.
   *
   * @param enable true to give the browser focus
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
   * Checks whether the browser has focus, as far as this browser told CEF.
   *
   * @return true if the browser has focus
   */
  public synchronized boolean hasFocus() {
    return this.focused;
  }

  /**
   * Gets the size of the page.
   *
   * @param browser the browser, which is this one
   * @return the size of the page at the origin
   */
  @Override
  public Rectangle getViewRect(final CefBrowser browser) {
    return new Rectangle(this.viewBounds);
  }

  /**
   * Leaves the screen information to CEF, which takes it from {@link #getViewRect}.
   *
   * @param browser    the browser, which is this one
   * @param screenInfo the screen information, left unchanged
   * @return false
   */
  @Override
  public boolean getScreenInfo(final CefBrowser browser, final CefScreenInfo screenInfo) {
    return false;
  }

  /**
   * Maps a point of the page to the screen, which the page covers exactly.
   *
   * @param browser   the browser, which is this one
   * @param viewPoint the point on the page
   * @return the same point
   */
  @Override
  public Point getScreenPoint(final CefBrowser browser, final Point viewPoint) {
    return new Point(viewPoint);
  }

  /**
   * Tells the listener that a popup widget, such as the list of a drop-down box, was shown or hidden.
   *
   * @param browser the browser, which is this one
   * @param show    true if it was shown
   */
  @Override
  public void onPopupShow(final CefBrowser browser, final boolean show) {
    this.listener.onPopupShow(show);
  }

  /**
   * Tells the listener where a popup widget is drawn.
   *
   * @param browser the browser, which is this one
   * @param size    the bounds of the popup on the page
   */
  @Override
  public void onPopupSize(final CefBrowser browser, final Rectangle size) {
    this.listener.onPopupSize(size);
  }

  /**
   * Hands a painted frame to the listener. The buffer belongs to CEF and is only valid during the call.
   *
   * @param browser    the browser, which is this one
   * @param popup      true if the buffer holds a popup widget rather than the page
   * @param dirtyRects the parts of the buffer that changed
   * @param buffer     the pixels, four bytes per pixel in BGRA order, row by row
   * @param width      the width of the buffer in pixels
   * @param height     the height of the buffer in pixels
   */
  @Override
  public void onPaint(
    final CefBrowser browser,
    final boolean popup,
    final Rectangle[] dirtyRects,
    final ByteBuffer buffer,
    final int width,
    final int height
  ) {
    this.listener.onPaint(popup, dirtyRects, buffer, width, height);
  }

  /**
   * Refuses paint event listeners; the frames go to the listener given when the browser was constructed.
   *
   * @param listener ignored
   * @throws UnsupportedOperationException always
   */
  @Override
  public void addOnPaintListener(final Consumer<CefPaintEvent> listener) {
    throw new UnsupportedOperationException("The frames go to the paint listener of the browser");
  }

  /**
   * Refuses paint event listeners; the frames go to the listener given when the browser was constructed.
   *
   * @param listener ignored
   * @throws UnsupportedOperationException always
   */
  @Override
  public void setOnPaintListener(final Consumer<CefPaintEvent> listener) {
    throw new UnsupportedOperationException("The frames go to the paint listener of the browser");
  }

  /**
   * Does nothing, as no paint event listener can be added.
   *
   * @param listener ignored
   */
  @Override
  public void removeOnPaintListener(final Consumer<CefPaintEvent> listener) {
    // no listener is ever added
  }

  /**
   * Ignores the cursor, which is not shown.
   *
   * @param browser    the browser, which is this one
   * @param cursorType the cursor CEF wants to show
   * @return true, so CEF does not try to show it itself
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
   * @param x        the x coordinate where the drag started
   * @param y        the y coordinate where the drag started
   * @return false, so CEF cancels the drag
   */
  @Override
  public boolean startDragging(final CefBrowser browser, final CefDragData dragData, final int mask, final int x, final int y) {
    return false;
  }

  /**
   * Ignores the drag cursor, as no drag is ever started.
   *
   * @param browser   the browser, which is this one
   * @param operation the current drag operation
   */
  @Override
  public void updateDragCursor(final CefBrowser browser, final int operation) {
    // no drag is ever started
  }

  /**
   * Receives the painted frames of a browser.
   */
  public interface PaintListener {
    /**
     * Receives a painted frame. The buffer belongs to CEF and is only valid during the call.
     *
     * @param popup      true if the buffer holds a popup widget rather than the page
     * @param dirtyRects the parts of the buffer that changed
     * @param buffer     the pixels, four bytes per pixel in BGRA order, row by row
     * @param width      the width of the buffer in pixels
     * @param height     the height of the buffer in pixels
     */
    void onPaint(boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height);

    /**
     * Receives that a popup widget was shown or hidden.
     *
     * @param show true if it was shown
     */
    void onPopupShow(boolean show);

    /**
     * Receives where a popup widget is drawn.
     *
     * @param bounds the bounds of the popup on the page
     */
    void onPopupSize(Rectangle bounds);
  }

  /**
   * A component that is never added to a window.
   */
  private static final class DetachedComponent extends Component {

    @Serial
    private static final long serialVersionUID = 1L;
  }
}

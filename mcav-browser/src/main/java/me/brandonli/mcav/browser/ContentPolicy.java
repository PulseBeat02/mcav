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

import java.util.Vector;
import java.util.function.Consumer;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefAuthCallback;
import org.cef.callback.CefBeforeDownloadCallback;
import org.cef.callback.CefCallback;
import org.cef.callback.CefContextMenuParams;
import org.cef.callback.CefDownloadItem;
import org.cef.callback.CefDownloadItemCallback;
import org.cef.callback.CefFileDialogCallback;
import org.cef.callback.CefJSDialogCallback;
import org.cef.callback.CefMenuModel;
import org.cef.handler.CefContextMenuHandler;
import org.cef.handler.CefDialogHandler;
import org.cef.handler.CefDownloadHandler;
import org.cef.handler.CefJSDialogHandler;
import org.cef.handler.CefLifeSpanHandler;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefRequestHandler;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.handler.CefResourceRequestHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.network.CefRequest;

/**
 * What the browser allows the web content it shows to do. The content is untrusted and runs without the Chromium
 * sandbox, so the browser keeps everything else away from it:
 *
 * <ul>
 *   <li>navigation only to {@code http} and {@code https} pages, and requests only to the web schemes
 *   ({@link NavigationPolicy});</li>
 *   <li>no new windows: a popup or a link opened in a new tab is opened in place instead, if its address is allowed;</li>
 *   <li>no downloads, no file choosers, no context menu, no external programs for unknown schemes;</li>
 *   <li>JavaScript dialogs are dismissed at once ({@code alert} confirmed, {@code confirm} and {@code prompt}
 *   cancelled), and leaving a page is always allowed;</li>
 *   <li>no credentials for authentication prompts, and requests with certificate errors fail.</li>
 * </ul>
 *
 * <p>Everything refused is reported as a notice, and a crashed renderer as a failure. Load state and load errors of
 * the page are reported too. The addresses in these reports are those of {@link AddressText}, without the parts that
 * may hold secrets.
 */
final class ContentPolicy
  implements
    CefLifeSpanHandler, CefRequestHandler, CefJSDialogHandler, CefDownloadHandler, CefDialogHandler, CefContextMenuHandler, CefLoadHandler {

  private final HelperEvents events;
  private final String engineVersion;
  private final Consumer<CefBrowser> created;
  private final CefResourceRequestHandler resourcePolicy;

  /**
   * Constructs the policy.
   *
   * @param events        receives what happens
   * @param engineVersion the version of CEF, reported when the browser was created
   * @param created       prepares the browser once it exists, before it loads the page
   */
  ContentPolicy(final HelperEvents events, final String engineVersion, final Consumer<CefBrowser> created) {
    this.events = events;
    this.engineVersion = engineVersion;
    this.created = created;
    this.resourcePolicy = new ResourcePolicy(events);
  }

  // CefLifeSpanHandler

  /**
   * Opens the address of a popup in place, if it may be shown, instead of opening a window.
   *
   * @return true, so no popup opens
   */
  @Override
  public boolean onBeforePopup(final CefBrowser browser, final CefFrame frame, final String targetUrl, final String targetFrameName) {
    this.openInPlace(browser, targetUrl);
    return true;
  }

  private void openInPlace(final CefBrowser browser, final String targetUrl) {
    final boolean allowed = NavigationPolicy.allowsNavigation(targetUrl, true);
    if (allowed) {
      browser.loadURL(targetUrl);
      this.events.onNotice("Opened a new window in place: " + AddressText.describe(targetUrl));
    } else {
      this.events.onNotice("Refused a new window: " + AddressText.describe(targetUrl));
    }
  }

  /**
   * Prepares the new browser, before it loads the page, and reports that it was created.
   */
  @Override
  public void onAfterCreated(final CefBrowser browser) {
    this.created.accept(browser);
    this.events.onReady(this.engineVersion);
  }

  /**
   * Does nothing; the browser has no parent window.
   */
  @Override
  public void onAfterParentChanged(final CefBrowser browser) {
    // an off-screen browser has no parent
  }

  /**
   * Lets the browser decide, which closes it only when the helper allowed it; a page cannot close its browser.
   *
   * @return false if the close goes ahead
   */
  @Override
  public boolean doClose(final CefBrowser browser) {
    return browser.doClose();
  }

  /**
   * Does nothing; the client cleans up after the browser.
   */
  @Override
  public void onBeforeClose(final CefBrowser browser) {
    // the client disposes of the browser
  }

  // CefRequestHandler

  /**
   * Cancels a navigation of the page to an address it may not show, or of a frame to one frames may not show.
   *
   * @return true to cancel the navigation
   */
  @Override
  public boolean onBeforeBrowse(
    final CefBrowser browser,
    final CefFrame frame,
    final CefRequest request,
    final boolean userGesture,
    final boolean isRedirect
  ) {
    final String url = request.getURL();
    final boolean mainFrame = frame.isMain();
    final boolean allowed = NavigationPolicy.allowsNavigation(url, mainFrame);
    if (!allowed) {
      this.events.onNotice("Refused a navigation to " + AddressText.describe(url));
    }
    return !allowed;
  }

  /**
   * Opens a link meant for a new tab in place, if a click or a key opened it and its address may be shown.
   *
   * @return true, so no tab opens
   */
  @Override
  public boolean onOpenURLFromTab(final CefBrowser browser, final CefFrame frame, final String targetUrl, final boolean userGesture) {
    // like a popup, a new tab needs a click or a key
    if (userGesture) {
      this.openInPlace(browser, targetUrl);
    }
    return true;
  }

  /**
   * Gets the policy of every request.
   *
   * @return the resource policy
   */
  @Override
  public CefResourceRequestHandler getResourceRequestHandler(
    final CefBrowser browser,
    final CefFrame frame,
    final CefRequest request,
    final boolean isNavigation,
    final boolean isDownload,
    final String requestInitiator,
    final BoolRef disableDefaultHandling
  ) {
    return this.resourcePolicy;
  }

  /**
   * Refuses to answer an authentication prompt, which fails the request.
   *
   * @return false
   */
  @Override
  public boolean getAuthCredentials(
    final CefBrowser browser,
    final String originUrl,
    final boolean isProxy,
    final String host,
    final int port,
    final String realm,
    final String scheme,
    final CefAuthCallback callback
  ) {
    this.events.onNotice("Refused to log in to " + host);
    return false;
  }

  /**
   * Fails a request whose certificate is not valid, as a browser without a user must.
   *
   * @return false
   */
  @Override
  public boolean onCertificateError(
    final CefBrowser browser,
    final CefLoadHandler.ErrorCode certError,
    final String requestUrl,
    final CefCallback callback
  ) {
    this.events.onNotice("Refused an invalid certificate of " + AddressText.describe(requestUrl));
    return false;
  }

  /**
   * Reports that the renderer of the page ended, which leaves nothing to show.
   */
  @Override
  public void onRenderProcessTerminated(
    final CefBrowser browser,
    final CefRequestHandler.TerminationStatus status,
    final int errorCode,
    final String errorString
  ) {
    this.events.onFailure("The renderer of the page ended: " + status + " (" + errorCode + ")");
  }

  // CefJSDialogHandler

  /**
   * Dismisses a JavaScript dialog at once: an alert is confirmed, a confirmation and a prompt are cancelled.
   *
   * @return true, as the dialog was handled
   */
  @Override
  public boolean onJSDialog(
    final CefBrowser browser,
    final String originUrl,
    final CefJSDialogHandler.JSDialogType dialogType,
    final String messageText,
    final String defaultPromptText,
    final CefJSDialogCallback callback,
    final BoolRef suppressMessage
  ) {
    final boolean alert = dialogType == CefJSDialogHandler.JSDialogType.JSDIALOGTYPE_ALERT;
    callback.Continue(alert, "");
    this.events.onNotice("Dismissed a JavaScript dialog of " + AddressText.describe(originUrl));
    return true;
  }

  /**
   * Allows leaving a page that asks whether to leave it.
   *
   * @return true, as the dialog was handled
   */
  @Override
  public boolean onBeforeUnloadDialog(
    final CefBrowser browser,
    final String messageText,
    final boolean isReload,
    final CefJSDialogCallback callback
  ) {
    callback.Continue(true, "");
    return true;
  }

  /**
   * Does nothing; no dialog is ever shown.
   */
  @Override
  public void onResetDialogState(final CefBrowser browser) {
    // no dialog is ever shown
  }

  /**
   * Does nothing; no dialog is ever shown.
   */
  @Override
  public void onDialogClosed(final CefBrowser browser) {
    // no dialog is ever shown
  }

  // CefDownloadHandler

  /**
   * Refuses a download, which an off-screen browser cancels by default.
   *
   * @return false for the default handling, which cancels the download
   */
  @Override
  public boolean onBeforeDownload(
    final CefBrowser browser,
    final CefDownloadItem downloadItem,
    final String suggestedName,
    final CefBeforeDownloadCallback callback
  ) {
    this.events.onNotice("Refused a download of " + suggestedName);
    return false;
  }

  /**
   * Cancels a download that started anyway.
   */
  @Override
  public void onDownloadUpdated(final CefBrowser browser, final CefDownloadItem downloadItem, final CefDownloadItemCallback callback) {
    callback.cancel();
  }

  // CefDialogHandler

  /**
   * Cancels a file chooser, so a page can neither read nor pick a file of the server.
   *
   * @return true, as the dialog was handled
   */
  @Override
  public boolean onFileDialog(
    final CefBrowser browser,
    final CefDialogHandler.FileDialogMode mode,
    final String title,
    final String defaultFilePath,
    final Vector<String> acceptFilters,
    final Vector<String> acceptExtensions,
    final Vector<String> acceptDescriptions,
    final CefFileDialogCallback callback
  ) {
    callback.Cancel();
    this.events.onNotice("Refused a file chooser");
    return true;
  }

  // CefContextMenuHandler

  /**
   * Removes every entry of the context menu, which could otherwise open developer tools or save files.
   */
  @Override
  public void onBeforeContextMenu(
    final CefBrowser browser,
    final CefFrame frame,
    final CefContextMenuParams params,
    final CefMenuModel model
  ) {
    model.clear();
  }

  /**
   * Runs no command, as the menu is empty.
   *
   * @return false
   */
  @Override
  public boolean onContextMenuCommand(
    final CefBrowser browser,
    final CefFrame frame,
    final CefContextMenuParams params,
    final int commandId,
    final int eventFlags
  ) {
    return false;
  }

  /**
   * Does nothing, as the menu is empty.
   */
  @Override
  public void onContextMenuDismissed(final CefBrowser browser, final CefFrame frame) {
    // the menu is empty
  }

  // CefLoadHandler

  /**
   * Reports whether the page is loading.
   */
  @Override
  public void onLoadingStateChange(final CefBrowser browser, final boolean isLoading, final boolean canGoBack, final boolean canGoForward) {
    // the blank document the browser is created with is not the page, nor is the empty address before it
    final String url = browser.getURL();
    if (url != null && !url.isEmpty() && !NavigationPolicy.BLANK.equals(url)) {
      this.events.onLoading(isLoading);
    }
  }

  /**
   * Does nothing; the load state is reported by {@link #onLoadingStateChange}.
   */
  @Override
  public void onLoadStart(final CefBrowser browser, final CefFrame frame, final CefRequest.TransitionType transitionType) {
    // reported by onLoadingStateChange
  }

  /**
   * Does nothing; the load state is reported by {@link #onLoadingStateChange}.
   */
  @Override
  public void onLoadEnd(final CefBrowser browser, final CefFrame frame, final int httpStatusCode) {
    // reported by onLoadingStateChange
  }

  /**
   * Reports that the page failed to load. Failures of frames inside the page are left to the page, and so is an
   * aborted load, which is a navigation that another one replaced or that the policy refused, as it reports itself.
   */
  @Override
  public void onLoadError(
    final CefBrowser browser,
    final CefFrame frame,
    final CefLoadHandler.ErrorCode errorCode,
    final String errorText,
    final String failedUrl
  ) {
    final boolean mainFrame = frame.isMain();
    if (mainFrame && errorCode != CefLoadHandler.ErrorCode.ERR_ABORTED) {
      final int code = errorCode.getCode();
      this.events.onLoadError(code, errorText, AddressText.describe(failedUrl));
    }
  }

  /**
   * The policy of every request: only the web schemes, and never an external program.
   */
  static final class ResourcePolicy extends CefResourceRequestHandlerAdapter {

    private final HelperEvents events;

    ResourcePolicy(final HelperEvents events) {
      this.events = events;
    }

    /**
     * Cancels a request to a scheme a page may not load.
     *
     * @return true to cancel the request
     */
    @Override
    public boolean onBeforeResourceLoad(final CefBrowser browser, final CefFrame frame, final CefRequest request) {
      final String url = request.getURL();
      final boolean allowed = NavigationPolicy.allowsRequest(url);
      if (!allowed) {
        this.events.onNotice("Refused a request to " + AddressText.describe(url));
      }
      return !allowed;
    }

    /**
     * Never hands an unknown scheme to a program of the operating system.
     */
    @Override
    public void onProtocolExecution(
      final CefBrowser browser,
      final CefFrame frame,
      final CefRequest request,
      final BoolRef allowOsExecution
    ) {
      allowOsExecution.set(false);
      final String url = request.getURL();
      this.events.onNotice("Refused to open " + AddressText.describe(url) + " with another program");
    }
  }
}

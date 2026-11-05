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
import org.cef.browser.MCAVOffscreenBrowser;
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

// The untrusted page runs without Chromium's sandbox, so all but web navigation is refused and reported, addresses through AddressText.
final class ContentPolicy
  implements
    CefLifeSpanHandler,
    CefRequestHandler,
    CefJSDialogHandler,
    CefDownloadHandler,
    CefDialogHandler,
    CefContextMenuHandler,
    CefLoadHandler
{

  private final HelperEvents events;
  private final String engineVersion;
  private final Consumer<CefBrowser> created;
  private final CefResourceRequestHandler resourcePolicy;

  ContentPolicy(final HelperEvents events, final String engineVersion, final Consumer<CefBrowser> created) {
    this.events = events;
    this.engineVersion = engineVersion;
    this.created = created;
    this.resourcePolicy = new ResourcePolicy(events);
  }

  // opens the address of the popup in place, if it may be shown
  @Override
  public boolean onBeforePopup(final CefBrowser browser, final CefFrame frame, final String targetUrl, final String targetFrameName) {
    this.openInPlace(browser, targetUrl);

    return true;
  }

  private void openInPlace(final CefBrowser browser, final String targetUrl) {
    final boolean allowed = NavigationPolicy.allowsNavigation(targetUrl, FrameKind.MAIN);
    if (allowed) {
      browser.loadURL(targetUrl);

      final String address = AddressText.describe(targetUrl);
      this.events.onNotice("Opened the new window %s in place.".formatted(address));
    } else {
      final String address = AddressText.describe(targetUrl);
      this.events.onNotice("The new window %s was refused.".formatted(address));
    }
  }

  @Override
  public void onAfterCreated(final CefBrowser browser) {
    this.created.accept(browser);
    this.events.onReady(this.engineVersion);
  }

  @Override
  public void onAfterParentChanged(final CefBrowser browser) {}

  // a page cannot close its browser; only the helper can
  @Override
  public boolean doClose(final CefBrowser browser) {
    return browser.doClose();
  }

  @Override
  public void onBeforeClose(final CefBrowser browser) {}

  @Override
  public boolean onBeforeBrowse(
    final CefBrowser browser,
    final CefFrame frame,
    final CefRequest request,
    final boolean isUserGesture,
    final boolean isRedirect
  ) {
    final String url = request.getURL();
    final boolean mainFrame = frame.isMain();
    final FrameKind kind = mainFrame ? FrameKind.MAIN : FrameKind.CHILD;
    final boolean allowed = NavigationPolicy.allowsNavigation(url, kind);
    if (!allowed) {
      final String address = AddressText.describe(url);
      this.events.onNotice("The navigation to %s was refused.".formatted(address));
    }

    return !allowed;
  }

  // in place, as popups are
  @Override
  public boolean onOpenURLFromTab(final CefBrowser browser, final CefFrame frame, final String targetUrl, final boolean isUserGesture) {
    if (isUserGesture) {
      this.openInPlace(browser, targetUrl);
    }

    return true;
  }

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
    this.events.onNotice("The login to %s was refused.".formatted(host));

    return false;
  }

  // no user can accept a bad certificate
  @Override
  public boolean onCertificateError(
    final CefBrowser browser,
    final CefLoadHandler.ErrorCode certificateError,
    final String requestUrl,
    final CefCallback callback
  ) {
    final String address = AddressText.describe(requestUrl);
    this.events.onNotice("The invalid certificate of %s was refused.".formatted(address));

    return false;
  }

  @Override
  public void onRenderProcessTerminated(
    final CefBrowser browser,
    final CefRequestHandler.TerminationStatus status,
    final int errorCode,
    final String errorString
  ) {
    this.events.onFailure("Status %s (error code %s) ended the renderer of the page".formatted(status, errorCode));
  }

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
    final boolean isAlert = dialogType == CefJSDialogHandler.JSDialogType.JSDIALOGTYPE_ALERT;
    callback.Continue(isAlert, "");

    final String address = AddressText.describe(originUrl);
    this.events.onNotice("A JavaScript dialog of %s was dismissed.".formatted(address));

    return true;
  }

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

  @Override
  public void onResetDialogState(final CefBrowser browser) {}

  @Override
  public void onDialogClosed(final CefBrowser browser) {}

  @Override
  public boolean onBeforeDownload(
    final CefBrowser browser,
    final CefDownloadItem downloadItem,
    final String suggestedName,
    final CefBeforeDownloadCallback callback
  ) {
    this.events.onNotice("The download of %s was refused.".formatted(suggestedName));

    // on macOS a page that starts a download while it loads stays white after the refusal until it changes
    if (browser instanceof final MCAVOffscreenBrowser offscreen) {
      offscreen.repaint();
    }

    return false;
  }

  @Override
  public void onDownloadUpdated(final CefBrowser browser, final CefDownloadItem downloadItem, final CefDownloadItemCallback callback) {
    callback.cancel();
  }

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
    this.events.onNotice("Refused a file chooser.");

    return true;
  }

  // the menu could open developer tools or save files
  @Override
  public void onBeforeContextMenu(
    final CefBrowser browser,
    final CefFrame frame,
    final CefContextMenuParams parameters,
    final CefMenuModel model
  ) {
    model.clear();
  }

  @Override
  public boolean onContextMenuCommand(
    final CefBrowser browser,
    final CefFrame frame,
    final CefContextMenuParams parameters,
    final int commandId,
    final int eventFlags
  ) {
    return false;
  }

  @Override
  public void onContextMenuDismissed(final CefBrowser browser, final CefFrame frame) {}

  @Override
  public void onLoadingStateChange(final CefBrowser browser, final boolean isLoading, final boolean canGoBack, final boolean canGoForward) {
    final String url = browser.getURL();
    if (url != null && !url.isEmpty() && !NavigationPolicy.BLANK.equals(url)) {
      this.events.onLoading(isLoading);
    }
  }

  // the sound of a new page waits for a press on it; frames and same-document navigations start no new page
  @Override
  public void onLoadStart(final CefBrowser browser, final CefFrame frame, final CefRequest.TransitionType transitionType) {
    if (frame.isMain()) {
      this.events.onPageStart();
    }
  }

  @Override
  public void onLoadEnd(final CefBrowser browser, final CefFrame frame, final int httpStatusCode) {}

  // failures of frames are the page's own; an aborted load was replaced or refused, which reports itself
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

  private static final class ResourcePolicy extends CefResourceRequestHandlerAdapter {

    private final HelperEvents events;

    ResourcePolicy(final HelperEvents events) {
      this.events = events;
    }

    @Override
    public boolean onBeforeResourceLoad(final CefBrowser browser, final CefFrame frame, final CefRequest request) {
      final String url = request.getURL();
      final boolean allowed = NavigationPolicy.allowsRequest(url);
      if (!allowed) {
        final String address = AddressText.describe(url);
        this.events.onNotice("The request to %s was refused.".formatted(address));
      }

      return !allowed;
    }

    @Override
    public void onProtocolExecution(
      final CefBrowser browser,
      final CefFrame frame,
      final CefRequest request,
      final BoolRef allowOsExecution
    ) {
      allowOsExecution.set(false);

      final String url = request.getURL();
      final String address = AddressText.describe(url);
      this.events.onNotice("Opening %s with another program was refused.".formatted(address));
    }
  }
}

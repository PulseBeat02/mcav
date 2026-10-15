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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.McavOffscreenBrowser;
import org.cef.callback.CefAuthCallback;
import org.cef.callback.CefBeforeDownloadCallback;
import org.cef.callback.CefCallback;
import org.cef.callback.CefContextMenuParams;
import org.cef.callback.CefDownloadItem;
import org.cef.callback.CefDownloadItemCallback;
import org.cef.callback.CefFileDialogCallback;
import org.cef.callback.CefJSDialogCallback;
import org.cef.callback.CefMenuModel;
import org.cef.handler.CefDialogHandler;
import org.cef.handler.CefJSDialogHandler;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefRequestHandler;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.misc.BoolRef;
import org.cef.network.CefRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

class ContentPolicyTest {

  private final RecordingEvents events = new RecordingEvents();
  private final List<CefBrowser> prepared = new ArrayList<>();
  private final ContentPolicy policy = new ContentPolicy(this.events, "146.0.10 (Chromium 146)", this.prepared::add);
  private final CefBrowser browser = mock(CefBrowser.class);

  private static CefFrame frame(final FrameKind kind) {
    final boolean isMain = kind == FrameKind.MAIN;
    final CefFrame frame = mock(CefFrame.class);
    when(frame.isMain()).thenReturn(isMain);

    return frame;
  }

  private static CefRequest request(final String url) {
    final CefRequest request = mock(CefRequest.class);
    when(request.getURL()).thenReturn(url);

    return request;
  }

  @Test
  void shouldOpenAPopupInPlaceWhenItsAddressIsAllowed() {
    final boolean cancelled = this.policy.onBeforePopup(this.browser, frame(FrameKind.MAIN), "https://example.com/popup", "_blank");
    assertTrue(cancelled);
    verify(this.browser).loadURL("https://example.com/popup");
    assertEquals(List.of("notice: Opened the new window https://example.com/popup in place."), this.events.log);
  }

  @Test
  void shouldRefuseAPopupToAnotherScheme() {
    final boolean popupCancelled = this.policy.onBeforePopup(this.browser, frame(FrameKind.MAIN), "file:///etc/passwd", "");
    assertTrue(popupCancelled);
    verify(this.browser, never()).loadURL(ArgumentMatchers.anyString());
    assertEquals(List.of("notice: The new window file:///etc/passwd was refused."), this.events.log);
  }

  @Test
  void shouldOpenALinkForANewTabInPlaceToo() {
    final boolean tabCancelled = this.policy.onOpenURLFromTab(this.browser, frame(FrameKind.MAIN), "http://example.com/tab", true);
    assertTrue(tabCancelled);
    verify(this.browser).loadURL("http://example.com/tab");
  }

  @Test
  void shouldCancelANewTabWithoutAClickOrAKey() {
    final boolean tabCancelled = this.policy.onOpenURLFromTab(this.browser, frame(FrameKind.MAIN), "http://example.com/tab", false);
    assertTrue(tabCancelled);
    verify(this.browser, never()).loadURL(ArgumentMatchers.anyString());
  }

  @Test
  void shouldPrepareTheCreatedBrowserAndReportItsEngine() {
    this.policy.onAfterCreated(this.browser);
    this.policy.onAfterParentChanged(this.browser);
    this.policy.onBeforeClose(this.browser);
    assertEquals(List.of(this.browser), this.prepared);
    assertEquals(List.of("ready: 146.0.10 (Chromium 146)"), this.events.log);
  }

  @Test
  void shouldLeaveClosingToTheBrowser() {
    when(this.browser.doClose()).thenReturn(true);
    final boolean handledWhenTheBrowserHandles = this.policy.doClose(this.browser);
    assertTrue(handledWhenTheBrowserHandles);
    when(this.browser.doClose()).thenReturn(false);
    final boolean handledWhenTheBrowserDoesNot = this.policy.doClose(this.browser);
    assertFalse(handledWhenTheBrowserDoesNot);
  }

  @Test
  void shouldFollowTheNavigationPolicyWhenNavigating() {
    final boolean httpsCancelled = this.policy.onBeforeBrowse(
      this.browser,
      frame(FrameKind.MAIN),
      request("https://example.com/"),
      true,
      false
    );
    assertFalse(httpsCancelled);
    final boolean fileCancelled = this.policy.onBeforeBrowse(
      this.browser,
      frame(FrameKind.MAIN),
      request("file:///etc/passwd"),
      false,
      true
    );
    assertTrue(fileCancelled);
    final boolean blankSubframeCancelled = this.policy.onBeforeBrowse(
      this.browser,
      frame(FrameKind.CHILD),
      request("about:blank"),
      false,
      false
    );
    assertFalse(blankSubframeCancelled);
    final boolean chromeSubframeCancelled = this.policy.onBeforeBrowse(
      this.browser,
      frame(FrameKind.CHILD),
      request("chrome://settings"),
      false,
      false
    );
    assertTrue(chromeSubframeCancelled);
    assertEquals(
      List.of("notice: The navigation to file:///etc/passwd was refused.", "notice: The navigation to chrome://settings was refused."),
      this.events.log
    );
  }

  @Test
  void shouldNeverReportTheUserTheQueryOrTheFragmentOfAnAddress() {
    final boolean customSchemeCancelled = this.policy.onBeforeBrowse(
      this.browser,
      frame(FrameKind.MAIN),
      request("myapp://callback?code=secret"),
      false,
      false
    );
    assertTrue(customSchemeCancelled);
    final CefResourceRequestHandler resources = this.policy.getResourceRequestHandler(
      this.browser,
      frame(FrameKind.MAIN),
      request("https://a/"),
      true,
      false,
      "",
      new BoolRef()
    );
    final boolean ftpRequestCancelled = resources.onBeforeResourceLoad(
      this.browser,
      frame(FrameKind.MAIN),
      request("ftp://user:password@example.com/file")
    );
    assertTrue(ftpRequestCancelled);
    final boolean certificateContinued = this.policy.onCertificateError(
      this.browser,
      CefLoadHandler.ErrorCode.ERR_CERT_DATE_INVALID,
      "https://expired/page#token=secret",
      mock(CefCallback.class)
    );
    assertFalse(certificateContinued);
    this.policy.onLoadError(
      this.browser,
      frame(FrameKind.MAIN),
      CefLoadHandler.ErrorCode.ERR_CONNECTION_REFUSED,
      "refused",
      "https://a/?sig=secret"
    );
    assertEquals(
      List.of(
        "notice: The navigation to myapp://callback?<hidden> was refused.",
        "notice: The request to ftp://<hidden>@example.com/file was refused.",
        "notice: The invalid certificate of https://expired/page#<hidden> was refused.",
        "load error: -102 refused https://a/?<hidden>"
      ),
      this.events.log
    );
  }

  @Test
  void shouldSendEveryRequestThroughTheResourcePolicy() {
    final CefResourceRequestHandler first = this.policy.getResourceRequestHandler(
      this.browser,
      frame(FrameKind.MAIN),
      request("https://a/"),
      true,
      false,
      "",
      new BoolRef()
    );
    final CefResourceRequestHandler second = this.policy.getResourceRequestHandler(
      this.browser,
      frame(FrameKind.CHILD),
      request("https://b/"),
      false,
      true,
      "https://a",
      new BoolRef()
    );
    assertSame(first, second);
    final boolean imageCancelled = first.onBeforeResourceLoad(this.browser, frame(FrameKind.MAIN), request("https://example.com/a.png"));
    assertFalse(imageCancelled);
    final boolean fileRequestCancelled = first.onBeforeResourceLoad(
      this.browser,
      frame(FrameKind.MAIN),
      request("file:///home/server/config.yml")
    );
    assertTrue(fileRequestCancelled);
    assertEquals(List.of("notice: The request to file:///home/server/config.yml was refused."), this.events.log);
  }

  @Test
  void shouldNeverHandAnUnknownSchemeToAnotherProgram() {
    final CefResourceRequestHandler resources = this.policy.getResourceRequestHandler(
      this.browser,
      frame(FrameKind.MAIN),
      request("steam://run/1"),
      true,
      false,
      "",
      new BoolRef()
    );
    final BoolRef allow = new BoolRef(true);
    resources.onProtocolExecution(this.browser, frame(FrameKind.MAIN), request("steam://run/1"), allow);
    final boolean executionAllowed = allow.get();
    assertFalse(executionAllowed);
    assertEquals(List.of("notice: Opening steam://run/1 with another program was refused."), this.events.log);
  }

  @Test
  void shouldFailTheRequestOnAuthenticationAndBadCertificates() {
    final CefAuthCallback authentication = mock(CefAuthCallback.class);
    final boolean credentialsGiven = this.policy.getAuthCredentials(
      this.browser,
      "https://a/",
      false,
      "a",
      443,
      "realm",
      "basic",
      authentication
    );
    assertFalse(credentialsGiven);
    verifyNoInteractions(authentication);
    final CefCallback certificate = mock(CefCallback.class);
    final boolean certificateContinued = this.policy.onCertificateError(
      this.browser,
      CefLoadHandler.ErrorCode.ERR_CERT_DATE_INVALID,
      "https://expired/",
      certificate
    );
    assertFalse(certificateContinued);
    verifyNoInteractions(certificate);
    assertEquals(
      List.of("notice: The login to a was refused.", "notice: The invalid certificate of https://expired/ was refused."),
      this.events.log
    );
  }

  @Test
  void shouldFailWhenTheRendererCrashes() {
    this.policy.onRenderProcessTerminated(this.browser, CefRequestHandler.TerminationStatus.TS_PROCESS_CRASHED, 11, "segfault");
    assertEquals(List.of("failure: The renderer of the page ended with status TS_PROCESS_CRASHED (11)"), this.events.log);
  }

  @Test
  void shouldDismissJavaScriptDialogsAtOnce() {
    final CefJSDialogCallback alert = mock(CefJSDialogCallback.class);
    final boolean alertHandled = this.policy.onJSDialog(
      this.browser,
      "https://a/",
      CefJSDialogHandler.JSDialogType.JSDIALOGTYPE_ALERT,
      "hi",
      "",
      alert,
      new BoolRef()
    );
    assertTrue(alertHandled);
    verify(alert).Continue(true, "");
    final CefJSDialogCallback confirm = mock(CefJSDialogCallback.class);
    final boolean confirmHandled = this.policy.onJSDialog(
      this.browser,
      "https://a/",
      CefJSDialogHandler.JSDialogType.JSDIALOGTYPE_CONFIRM,
      "ok?",
      "",
      confirm,
      new BoolRef()
    );
    assertTrue(confirmHandled);
    verify(confirm).Continue(false, "");
    final CefJSDialogCallback prompt = mock(CefJSDialogCallback.class);
    final boolean promptHandled = this.policy.onJSDialog(
      this.browser,
      "https://a/",
      CefJSDialogHandler.JSDialogType.JSDIALOGTYPE_PROMPT,
      "name?",
      "x",
      prompt,
      new BoolRef()
    );
    assertTrue(promptHandled);
    verify(prompt).Continue(false, "");
    final CefJSDialogCallback unload = mock(CefJSDialogCallback.class);
    final boolean unloadHandled = this.policy.onBeforeUnloadDialog(this.browser, "leave?", false, unload);
    assertTrue(unloadHandled);
    verify(unload).Continue(true, "");
    this.policy.onResetDialogState(this.browser);
    this.policy.onDialogClosed(this.browser);
    final int eventCount = this.events.log.size();
    assertEquals(3, eventCount);
  }

  @Test
  void shouldRefuseAndCancelDownloads() {
    final CefBeforeDownloadCallback before = mock(CefBeforeDownloadCallback.class);
    final boolean downloadHandled = this.policy.onBeforeDownload(this.browser, mock(CefDownloadItem.class), "evil.exe", before);
    assertFalse(downloadHandled);
    verifyNoInteractions(before);
    final CefDownloadItemCallback updated = mock(CefDownloadItemCallback.class);
    this.policy.onDownloadUpdated(this.browser, mock(CefDownloadItem.class), updated);
    verify(updated).cancel();
    assertEquals(List.of("notice: The download of evil.exe was refused."), this.events.log);
  }

  // On macOS the view of a page that started a download while it loaded stayed white after the download was refused,
  // until the page changed; the refusal asks the off-screen browser to paint its view again.
  @Test
  void shouldRepaintTheViewAfterARefusedDownload() {
    final McavOffscreenBrowser offscreen = mock(McavOffscreenBrowser.class);
    final boolean handled = this.policy.onBeforeDownload(
      offscreen,
      mock(CefDownloadItem.class),
      "evil.exe",
      mock(CefBeforeDownloadCallback.class)
    );
    assertFalse(handled);
    verify(offscreen).repaint();
  }

  @Test
  void shouldCancelFileChoosers() {
    final CefFileDialogCallback callback = mock(CefFileDialogCallback.class);
    final boolean handled = this.policy.onFileDialog(
      this.browser,
      CefDialogHandler.FileDialogMode.FILE_DIALOG_OPEN,
      "Pick",
      "",
      null,
      null,
      null,
      callback
    );
    assertTrue(handled);
    verify(callback).Cancel();
    assertEquals(List.of("notice: Refused a file chooser."), this.events.log);
  }

  @Test
  void shouldEmptyTheContextMenu() {
    final CefMenuModel model = mock(CefMenuModel.class);
    this.policy.onBeforeContextMenu(this.browser, frame(FrameKind.MAIN), mock(CefContextMenuParams.class), model);
    verify(model).clear();
    final boolean commandHandled = this.policy.onContextMenuCommand(
      this.browser,
      frame(FrameKind.MAIN),
      mock(CefContextMenuParams.class),
      1,
      0
    );
    assertFalse(commandHandled);
    this.policy.onContextMenuDismissed(this.browser, frame(FrameKind.MAIN));
  }

  @Test
  void shouldNotReportTheBlankDocumentBeforeThePageOrAbortedLoads() {
    final CefBrowser blank = mock(CefBrowser.class);
    when(blank.getURL()).thenReturn("");
    this.policy.onLoadingStateChange(blank, true, false, false);
    when(blank.getURL()).thenReturn(null);
    this.policy.onLoadingStateChange(blank, false, false, false);
    when(blank.getURL()).thenReturn("about:blank");
    this.policy.onLoadingStateChange(blank, true, false, false);
    this.policy.onLoadingStateChange(blank, false, false, false);
    this.policy.onLoadError(blank, frame(FrameKind.MAIN), CefLoadHandler.ErrorCode.ERR_ABORTED, "aborted", "https://replaced/");
    when(blank.getURL()).thenReturn("https://example.com/");
    this.policy.onLoadingStateChange(blank, false, false, false);
    assertEquals(List.of("loading: false"), this.events.log);
  }

  @Test
  void shouldReportTheLoadStateAndErrorsOfThePage() {
    when(this.browser.getURL()).thenReturn("https://x.invalid/");
    this.policy.onLoadingStateChange(this.browser, true, false, false);
    this.policy.onLoadStart(this.browser, frame(FrameKind.MAIN), CefRequest.TransitionType.TT_EXPLICIT);
    this.policy.onLoadEnd(this.browser, frame(FrameKind.MAIN), 200);
    this.policy.onLoadError(this.browser, frame(FrameKind.CHILD), CefLoadHandler.ErrorCode.ERR_ABORTED, "aborted", "https://frame/");
    this.policy.onLoadError(
      this.browser,
      frame(FrameKind.MAIN),
      CefLoadHandler.ErrorCode.ERR_NAME_NOT_RESOLVED,
      "no such host",
      "https://x.invalid/"
    );
    this.policy.onLoadingStateChange(this.browser, false, true, false);
    assertEquals(
      List.of("loading: true", "page start", "load error: -105 no such host https://x.invalid/", "loading: false"),
      this.events.log
    );
  }

  @Test
  void shouldOnlyStartAPageOnANewPageInTheMainFrame() {
    this.policy.onLoadStart(this.browser, frame(FrameKind.CHILD), CefRequest.TransitionType.TT_AUTO_SUBFRAME);
    assertEquals(List.of(), this.events.log, "a frame inside the page is part of it");
    this.policy.onLoadStart(this.browser, frame(FrameKind.MAIN), CefRequest.TransitionType.TT_LINK);
    assertEquals(List.of("page start"), this.events.log);
  }

  static final class RecordingEvents implements HelperEvents {

    final List<String> log = new ArrayList<>();

    @Override
    public void onReady(final String engineVersion) {
      this.log.add("ready: " + engineVersion);
    }

    @Override
    public void onPageStart() {
      this.log.add("page start");
    }

    @Override
    public void onLoading(final boolean loading) {
      this.log.add("loading: " + loading);
    }

    @Override
    public void onLoadError(final int code, final String text, final String url) {
      this.log.add("load error: " + code + " " + text + " " + url);
    }

    @Override
    public void onNotice(final String text) {
      this.log.add("notice: " + text);
    }

    @Override
    public void onFailure(final String text) {
      this.log.add("failure: " + text);
    }

    @Override
    public void onAudio(final byte[] samples) {
      this.log.add("audio: " + samples.length);
    }
  }
}

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

class ContentPolicyTest {

  private final RecordingEvents events = new RecordingEvents();
  private final List<CefBrowser> prepared = new ArrayList<>();
  private final ContentPolicy policy = new ContentPolicy(this.events, "146.0.10 (Chromium 146)", this.prepared::add);
  private final CefBrowser browser = mock(CefBrowser.class);

  private static CefFrame frame(final boolean main) {
    final CefFrame frame = mock(CefFrame.class);
    when(frame.isMain()).thenReturn(main);
    return frame;
  }

  private static CefRequest request(final String url) {
    final CefRequest request = mock(CefRequest.class);
    when(request.getURL()).thenReturn(url);
    return request;
  }

  @Test
  void aPopupOpensInPlaceWhenItsAddressIsAllowed() {
    final boolean cancelled = this.policy.onBeforePopup(this.browser, frame(true), "https://example.com/popup", "_blank");
    assertTrue(cancelled);
    verify(this.browser).loadURL("https://example.com/popup");
    assertEquals(List.of("notice: Opened a new window in place: https://example.com/popup"), this.events.log);
  }

  @Test
  void aPopupToAnotherSchemeIsRefused() {
    assertTrue(this.policy.onBeforePopup(this.browser, frame(true), "file:///etc/passwd", ""));
    verify(this.browser, never()).loadURL(org.mockito.ArgumentMatchers.anyString());
    assertEquals(List.of("notice: Refused a new window: file:///etc/passwd"), this.events.log);
  }

  @Test
  void aLinkForANewTabOpensInPlaceToo() {
    assertTrue(this.policy.onOpenURLFromTab(this.browser, frame(true), "http://example.com/tab", true));
    verify(this.browser).loadURL("http://example.com/tab");
  }

  @Test
  void theCreatedBrowserIsPreparedAndReportsItsEngine() {
    this.policy.onAfterCreated(this.browser);
    this.policy.onAfterParentChanged(this.browser);
    this.policy.onBeforeClose(this.browser);
    assertEquals(List.of(this.browser), this.prepared);
    assertEquals(List.of("ready: 146.0.10 (Chromium 146)"), this.events.log);
  }

  @Test
  void closingIsLeftToTheBrowser() {
    when(this.browser.doClose()).thenReturn(true);
    assertTrue(this.policy.doClose(this.browser));
    when(this.browser.doClose()).thenReturn(false);
    assertFalse(this.policy.doClose(this.browser));
  }

  @Test
  void navigationFollowsTheNavigationPolicy() {
    assertFalse(this.policy.onBeforeBrowse(this.browser, frame(true), request("https://example.com/"), true, false));
    assertTrue(this.policy.onBeforeBrowse(this.browser, frame(true), request("file:///etc/passwd"), false, true));
    assertFalse(this.policy.onBeforeBrowse(this.browser, frame(false), request("about:blank"), false, false));
    assertTrue(this.policy.onBeforeBrowse(this.browser, frame(false), request("chrome://settings"), false, false));
    assertEquals(
      List.of("notice: Refused a navigation to file:///etc/passwd", "notice: Refused a navigation to chrome://settings"),
      this.events.log
    );
  }

  @Test
  void everyRequestGoesThroughTheResourcePolicy() {
    final CefResourceRequestHandler first =
      this.policy.getResourceRequestHandler(this.browser, frame(true), request("https://a/"), true, false, "", new BoolRef());
    final CefResourceRequestHandler second =
      this.policy.getResourceRequestHandler(this.browser, frame(false), request("https://b/"), false, true, "https://a", new BoolRef());
    assertSame(first, second);
    assertFalse(first.onBeforeResourceLoad(this.browser, frame(true), request("https://example.com/a.png")));
    assertTrue(first.onBeforeResourceLoad(this.browser, frame(true), request("file:///home/server/config.yml")));
    assertEquals(List.of("notice: Refused a request to file:///home/server/config.yml"), this.events.log);
  }

  @Test
  void anUnknownSchemeIsNeverHandedToAnotherProgram() {
    final CefResourceRequestHandler resources =
      this.policy.getResourceRequestHandler(this.browser, frame(true), request("steam://run/1"), true, false, "", new BoolRef());
    final BoolRef allow = new BoolRef(true);
    resources.onProtocolExecution(this.browser, frame(true), request("steam://run/1"), allow);
    assertFalse(allow.get());
    assertEquals(List.of("notice: Refused to open steam://run/1 with another program"), this.events.log);
  }

  @Test
  void authenticationAndBadCertificatesFailTheRequest() {
    final CefAuthCallback auth = mock(CefAuthCallback.class);
    assertFalse(this.policy.getAuthCredentials(this.browser, "https://a/", false, "a", 443, "realm", "basic", auth));
    verifyNoInteractions(auth);
    final CefCallback certificate = mock(CefCallback.class);
    assertFalse(
      this.policy.onCertificateError(this.browser, CefLoadHandler.ErrorCode.ERR_CERT_DATE_INVALID, "https://expired/", certificate)
    );
    verifyNoInteractions(certificate);
    assertEquals(List.of("notice: Refused to log in to a", "notice: Refused an invalid certificate of https://expired/"), this.events.log);
  }

  @Test
  void aCrashedRendererIsAFailure() {
    this.policy.onRenderProcessTerminated(this.browser, CefRequestHandler.TerminationStatus.TS_PROCESS_CRASHED, 11, "segfault");
    assertEquals(List.of("failure: The renderer of the page ended: TS_PROCESS_CRASHED (11)"), this.events.log);
  }

  @Test
  void javaScriptDialogsAreDismissedAtOnce() {
    final CefJSDialogCallback alert = mock(CefJSDialogCallback.class);
    assertTrue(
      this.policy.onJSDialog(this.browser, "https://a/", CefJSDialogHandler.JSDialogType.JSDIALOGTYPE_ALERT, "hi", "", alert, new BoolRef())
    );
    verify(alert).Continue(true, "");
    final CefJSDialogCallback confirm = mock(CefJSDialogCallback.class);
    assertTrue(
      this.policy.onJSDialog(
          this.browser,
          "https://a/",
          CefJSDialogHandler.JSDialogType.JSDIALOGTYPE_CONFIRM,
          "ok?",
          "",
          confirm,
          new BoolRef()
        )
    );
    verify(confirm).Continue(false, "");
    final CefJSDialogCallback prompt = mock(CefJSDialogCallback.class);
    assertTrue(
      this.policy.onJSDialog(
          this.browser,
          "https://a/",
          CefJSDialogHandler.JSDialogType.JSDIALOGTYPE_PROMPT,
          "name?",
          "x",
          prompt,
          new BoolRef()
        )
    );
    verify(prompt).Continue(false, "");
    final CefJSDialogCallback unload = mock(CefJSDialogCallback.class);
    assertTrue(this.policy.onBeforeUnloadDialog(this.browser, "leave?", false, unload));
    verify(unload).Continue(true, "");
    this.policy.onResetDialogState(this.browser);
    this.policy.onDialogClosed(this.browser);
    assertEquals(3, this.events.log.size());
  }

  @Test
  void downloadsAreRefusedAndCancelled() {
    final CefBeforeDownloadCallback before = mock(CefBeforeDownloadCallback.class);
    assertFalse(this.policy.onBeforeDownload(this.browser, mock(CefDownloadItem.class), "evil.exe", before));
    verifyNoInteractions(before);
    final CefDownloadItemCallback updated = mock(CefDownloadItemCallback.class);
    this.policy.onDownloadUpdated(this.browser, mock(CefDownloadItem.class), updated);
    verify(updated).cancel();
    assertEquals(List.of("notice: Refused a download of evil.exe"), this.events.log);
  }

  @Test
  void fileChoosersAreCancelled() {
    final CefFileDialogCallback callback = mock(CefFileDialogCallback.class);
    final boolean handled =
      this.policy.onFileDialog(this.browser, CefDialogHandler.FileDialogMode.FILE_DIALOG_OPEN, "Pick", "", null, null, null, callback);
    assertTrue(handled);
    verify(callback).Cancel();
    assertEquals(List.of("notice: Refused a file chooser"), this.events.log);
  }

  @Test
  void theContextMenuIsEmpty() {
    final CefMenuModel model = mock(CefMenuModel.class);
    this.policy.onBeforeContextMenu(this.browser, frame(true), mock(CefContextMenuParams.class), model);
    verify(model).clear();
    assertFalse(this.policy.onContextMenuCommand(this.browser, frame(true), mock(CefContextMenuParams.class), 1, 0));
    this.policy.onContextMenuDismissed(this.browser, frame(true));
  }

  @Test
  void theBlankDocumentBeforeThePageAndAbortedLoadsAreNotReported() {
    final CefBrowser blank = mock(CefBrowser.class);
    when(blank.getURL()).thenReturn("about:blank");
    this.policy.onLoadingStateChange(blank, true, false, false);
    this.policy.onLoadingStateChange(blank, false, false, false);
    this.policy.onLoadError(blank, frame(true), CefLoadHandler.ErrorCode.ERR_ABORTED, "aborted", "https://replaced/");
    when(blank.getURL()).thenReturn("https://example.com/");
    this.policy.onLoadingStateChange(blank, false, false, false);
    assertEquals(List.of("loading: false"), this.events.log);
  }

  @Test
  void theLoadStateAndErrorsOfThePageAreReported() {
    this.policy.onLoadingStateChange(this.browser, true, false, false);
    this.policy.onLoadStart(this.browser, frame(true), CefRequest.TransitionType.TT_EXPLICIT);
    this.policy.onLoadEnd(this.browser, frame(true), 200);
    this.policy.onLoadError(this.browser, frame(false), CefLoadHandler.ErrorCode.ERR_ABORTED, "aborted", "https://frame/");
    this.policy.onLoadError(
        this.browser,
        frame(true),
        CefLoadHandler.ErrorCode.ERR_NAME_NOT_RESOLVED,
        "no such host",
        "https://x.invalid/"
      );
    this.policy.onLoadingStateChange(this.browser, false, true, false);
    assertEquals(List.of("loading: true", "load error: -105 no such host https://x.invalid/", "loading: false"), this.events.log);
  }

  /**
   * Records every event as a line.
   */
  static final class RecordingEvents implements HelperEvents {

    final List<String> log = new ArrayList<>();

    @Override
    public void onReady(final String engineVersion) {
      this.log.add("ready: " + engineVersion);
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
  }
}

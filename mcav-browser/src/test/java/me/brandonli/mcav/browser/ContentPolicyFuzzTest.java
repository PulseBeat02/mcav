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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.util.ArrayList;
import java.util.List;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.network.CefRequest;
import org.junit.jupiter.api.Tag;

@Tag("fuzz")
final class ContentPolicyFuzzTest {

  private static final String ENGINE_VERSION = "146.0.10 (Chromium 146)";

  private static final String FRAME_NAME = "_blank";

  private static final int ONE_NOTICE = 1;

  @FuzzTest(maxDuration = "30s")
  void shouldOpenAPopupInPlaceOnlyAtAnAllowedAddress(final FuzzedDataProvider data) {
    final String address = data.consumeRemainingAsString();
    final ContentPolicyTest.RecordingEvents events = new ContentPolicyTest.RecordingEvents();
    final ContentPolicy policy = this.createPolicy(events);
    final CefBrowser browser = mock(CefBrowser.class);
    final CefFrame frame = this.createFrame(FrameKind.MAIN);
    final boolean cancelled = policy.onBeforePopup(browser, frame, address, FRAME_NAME);
    assertTrue(cancelled, address);
    this.assertOpenedOnlyWhenAllowed(browser, address);
    assertEquals(ONE_NOTICE, events.log.size(), address);
  }

  @FuzzTest(maxDuration = "30s")
  void shouldOpenATabInPlaceOnlyAtAnAllowedAddress(final FuzzedDataProvider data) {
    final String address = data.consumeRemainingAsString();
    final ContentPolicyTest.RecordingEvents events = new ContentPolicyTest.RecordingEvents();
    final ContentPolicy policy = this.createPolicy(events);
    final CefBrowser browser = mock(CefBrowser.class);
    final CefFrame frame = this.createFrame(FrameKind.MAIN);
    final boolean cancelled = policy.onOpenURLFromTab(browser, frame, address, true);
    assertTrue(cancelled, address);
    this.assertOpenedOnlyWhenAllowed(browser, address);
  }

  @FuzzTest(maxDuration = "30s")
  void shouldCancelANavigationExactlyWhenThePolicyRefusesIt(final FuzzedDataProvider data) {
    final boolean isMain = data.consumeBoolean();
    final String address = data.consumeRemainingAsString();
    final FrameKind kind = isMain ? FrameKind.MAIN : FrameKind.CHILD;
    final ContentPolicyTest.RecordingEvents events = new ContentPolicyTest.RecordingEvents();
    final ContentPolicy policy = this.createPolicy(events);
    final CefBrowser browser = mock(CefBrowser.class);
    final CefFrame frame = this.createFrame(kind);
    final CefRequest request = mock(CefRequest.class);
    when(request.getURL()).thenReturn(address);
    final boolean cancelled = policy.onBeforeBrowse(browser, frame, request, true, false);
    final boolean allowed = NavigationPolicy.allowsNavigation(address, kind);
    assertEquals(!allowed, cancelled, address);
  }

  private ContentPolicy createPolicy(final ContentPolicyTest.RecordingEvents events) {
    final List<CefBrowser> created = new ArrayList<>();
    return new ContentPolicy(events, ENGINE_VERSION, created::add);
  }

  private CefFrame createFrame(final FrameKind kind) {
    final boolean isMain = kind == FrameKind.MAIN;
    final CefFrame frame = mock(CefFrame.class);
    when(frame.isMain()).thenReturn(isMain);

    return frame;
  }

  private void assertOpenedOnlyWhenAllowed(final CefBrowser browser, final String address) {
    final boolean allowed = NavigationPolicy.allowsNavigation(address, FrameKind.MAIN);
    final int expectedLoads = allowed ? 1 : 0;
    verify(browser, times(expectedLoads)).loadURL(address);
  }
}

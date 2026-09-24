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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.mockito.AdditionalAnswers;

/**
 * One real Playwright/Chromium pair per class. A lease serializes ALL access across the successive player threads,
 * as allowed by Playwright's Java multithreading contract; every test gets a fresh isolated BrowserContext.
 */
final class SharedPlaywright implements AutoCloseable {

  private final ReentrantLock owner = new ReentrantLock();
  private final Playwright playwright;
  private final Browser browser;

  SharedPlaywright() {
    ExternalBrowsers.assumePlaywrightBrowser();
    final Map<String, String> environment = PlaywrightInstaller.getEnvironment();
    final Playwright.CreateOptions options = new Playwright.CreateOptions();
    options.setEnv(environment);
    this.playwright = Playwright.create(options);
    try {
      final BrowserType chromium = this.playwright.chromium();
      final BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions();
      launchOptions.setHeadless(true);
      this.browser = chromium.launch(launchOptions);
    } catch (final RuntimeException | Error failure) {
      this.playwright.close();
      throw failure;
    }
  }

  Playwright lease() {
    if (!this.owner.tryLock()) {
      throw new IllegalStateException("The previous Playwright session has not released its lease");
    }
    try {
      final Browser sessionBrowser = mock(Browser.class, AdditionalAnswers.delegatesTo(this.browser));
      // BrowserResources calls this on its owner thread, after detaching the screencast and ending the pump.
      doAnswer(_ -> {
        this.closeContexts();
        return null;
      })
        .when(sessionBrowser)
        .close();
      final BrowserType realChromium = this.playwright.chromium();
      final BrowserType chromium = mock(BrowserType.class, AdditionalAnswers.delegatesTo(realChromium));
      doReturn(sessionBrowser).when(chromium).launch(any(BrowserType.LaunchOptions.class));
      final Playwright connection = mock(Playwright.class, AdditionalAnswers.delegatesTo(this.playwright));
      doReturn(chromium).when(connection).chromium();
      doAnswer(_ -> {
        this.owner.unlock();
        return null;
      })
        .when(connection)
        .close();
      return connection;
    } catch (final RuntimeException | Error failure) {
      this.owner.unlock();
      throw failure;
    }
  }

  private void closeContexts() {
    final List<BrowserContext> contexts = this.browser.contexts();
    for (final BrowserContext context : contexts) {
      context.close();
    }
  }

  void assertReleased() {
    if (!this.owner.tryLock()) {
      throw new IllegalStateException("A player kept its Playwright lease after test cleanup");
    }
    this.owner.unlock();
  }

  @Override
  public void close() {
    if (!this.owner.tryLock()) {
      throw new IllegalStateException("A player kept its Playwright lease after test cleanup");
    }
    try {
      this.browser.close();
    } finally {
      try {
        this.playwright.close();
      } finally {
        this.owner.unlock();
      }
    }
  }
}

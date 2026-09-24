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

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.mockito.AdditionalAnswers;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.devtools.DevTools;

/** A class-owned real Chrome process; each player owns only its listeners and worker threads. */
final class SharedChrome implements AutoCloseable {

  private final ChromeDriver chrome;
  private final AtomicBoolean leased = new AtomicBoolean();

  SharedChrome() {
    ExternalBrowsers.assumeChrome();
    final String[] arguments = BrowserPlayer.DEFAULT_CHROME_ARGUMENTS.toArray(String[]::new);
    final List<String> resolved = ChromeArguments.resolve(arguments);
    final ChromeOptions options = new ChromeOptions();
    options.addArguments(resolved);
    this.chrome = new ChromeDriver(ChromeDriverProvider.getService(), options);
  }

  SeleniumPlayer player() {
    final String[] arguments = BrowserPlayer.DEFAULT_CHROME_ARGUMENTS.toArray(String[]::new);
    return new SeleniumPlayer(_ -> this.lease(), arguments);
  }

  private ChromeDriver lease() {
    if (!this.leased.compareAndSet(false, true)) {
      throw new IllegalStateException("The previous Chrome session has not released its lease");
    }
    try {
      this.resetPages();
      final DevTools realTools = this.chrome.getDevTools();
      final DevTools tools = mock(DevTools.class, AdditionalAnswers.delegatesTo(realTools));
      // A player closes its CDP session, but only the class fixture may close the shared transport/browser.
      doAnswer(_ -> {
        realTools.disconnectSession();
        return null;
      })
        .when(tools)
        .close();
      final ChromeDriver driver = mock(ChromeDriver.class, AdditionalAnswers.delegatesTo(this.chrome));
      doReturn(tools).when(driver).getDevTools();
      doAnswer(_ -> {
        this.leased.set(false);
        return null;
      })
        .when(driver)
        .quit();
      return driver;
    } catch (final RuntimeException | Error failure) {
      this.leased.set(false);
      throw failure;
    }
  }

  private void resetPages() {
    final Set<String> handles = this.chrome.getWindowHandles();
    final List<String> open = new ArrayList<>(handles);
    final WebDriver.TargetLocator windows = this.chrome.switchTo();
    final String kept = open.getFirst();
    for (final String handle : open) {
      windows.window(handle);
      final String address = this.chrome.getCurrentUrl();
      if (address.startsWith("http://") || address.startsWith("https://")) {
        this.chrome.executeScript("window.localStorage.clear(); window.sessionStorage.clear();");
      }
      if (!handle.equals(kept)) {
        this.chrome.close();
      }
    }
    windows.window(kept);
    this.chrome.get("about:blank");
    final WebDriver.Options options = this.chrome.manage();
    options.deleteAllCookies();
  }

  void assertReleased() {
    if (this.leased.get()) {
      throw new IllegalStateException("A player kept its Chrome lease after test cleanup");
    }
  }

  @Override
  public void close() {
    try {
      this.assertReleased();
    } finally {
      this.chrome.quit();
    }
  }
}

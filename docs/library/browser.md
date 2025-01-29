# Browser Module

MCAV provides a browser in a separate module called `mcav-browser`. To use the browser player, you must import the MCAV
browser module, which uses [Selenium](https://www.selenium.dev/).

```kotlin
dependencies {
    implementation("me.brandonli:mcav-browser:1.0.0-SNAPSHOT")
}
```

The browser module provides two browser implementations. The `SeleniumPlayer` and the `PlaywrightPlayer`. The `SeleniumPlayer`
uses the [Selenium WebDriver](https://www.selenium.dev/documentation/webdriver/) to control a web browser, while the
`PlaywrightPlayer` uses the [Playwright](https://playwright.dev/) library to control a web browser.

Each browser player has its own advantages. Unfortunately, audio is not supported in both players due to limitations in the
Chrome WebDriver. To use either browser, you must have a `BrowserSource` that specifies the URL of the web
page to connect to.

```java
  final VideoPipelineStep videoPipelineStep = ...;
  final BrowserSource browserSource = BrowserSource.uri(URI.create("https://www.google.com"), 100, 1920, 1080, 1);
  final BrowserPlayer player = BrowserPlayer.defaultSelenium(); // starts Selenium WebDriver with default arguments
  player.start(videoPipelineStep, browserSource);
  // ... do something with the player
  player.release();
```

To interact with the browser, you can use methods like the `sendMouseEvent` to send a mouse click or the `sendKeyEvent`
to send keyboard input into the browser. The `sendMouseEvent` provides an enum `MouseClick` to specify the type of mouse
click you want to use. For sending keys, you must use 
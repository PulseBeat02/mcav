# Browser Module

MCAV provides a browser in a separate module called `mcav-browser`. To use the browser player, you must import the MCAV
browser module, which uses [Selenium](https://www.selenium.dev/).

```kotlin
dependencies {
    implementation("me.brandonli:mcav-browser:1.0.0-SNAPSHOT")
}
```

The browser module provides a `ChromeDriverPlayer`, which is a video player that uses
the [Chrome WebDriver](https://developer.chrome.com/docs/chromedriver) to
screencast a web page from a browser. Unfortunately, audio is not supported in this player due to limitations in the
Chrome WebDriver. To use the `ChromeDriverPlayer`, you must have a `BrowserSource` that specifies the URL of the web
page to connect to.

```java
  final VideoPipelineStep videoPipelineStep = ...;
final BrowserSource browserSource = BrowserSource.uri(URI.create("https://www.google.com"), 100, 1920, 1080, 1);
final BrowserPlayer player = BrowserPlayer.defaultChrome(); // starts Chrome WebDriver with default arguments
  player.

start(videoPipelineStep, browserSource);
// ... do something with the player
  player.

release();
```

To interact with the browser, you can use methods like the `sendMouseEvent` to send a mouse click or the `sendKeyEvent`
to send keyboard input into the browser. The `sendMouseEvent` provides an enum `MouseClick` to specify the type of mouse
click you want to use.

For convinence, the `sendKeyEvent` will automatically parse special keys into their respective keycode. A list of
special characters can be found [here](https://www.selenium.dev/selenium/docs/api/java/org/openqa/selenium/Keys.html).
For example, passing `ENTER` into `sendKeyEvent` will replace the `ENTER` input with `\uE007`. You can escape them using
the backslash character.
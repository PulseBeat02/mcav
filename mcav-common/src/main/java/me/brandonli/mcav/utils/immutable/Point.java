package me.brandonli.mcav.utils.immutable;

public final class Point {
  private final double x;
  private final double y;

  public Point(final double x, final double y) {
    this.x = x;
    this.y = y;
  }

  public double getX() {
    return this.x;
  }

  public double getY() {
    return this.y;
  }
}

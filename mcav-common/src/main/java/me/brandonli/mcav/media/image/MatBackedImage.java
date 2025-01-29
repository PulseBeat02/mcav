/*
 * This file is part of mcav, a media playback library for Minecraft
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
package me.brandonli.mcav.media.image;

import static org.opencv.imgcodecs.Imgcodecs.imread;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import me.brandonli.mcav.media.source.FileSource;
import me.brandonli.mcav.media.source.UriSource;
import me.brandonli.mcav.utils.IOUtils;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameUtils;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.opencv.core.*;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;
import org.opencv.photo.Photo;

/**
 * A class that represents an image backed by an OpenCV Mat object. It provides various image
 * processing methods such as resizing, rotating, flipping, and applying filters.
 */
public class MatBackedImage implements StaticImage {

  private final Mat mat;

  MatBackedImage(final byte[] bytes) {
    final Mat originalMat = new Mat();
    originalMat.put(0, 0, bytes);
    this.mat = originalMat;
  }

  MatBackedImage(final UriSource source) throws IOException {
    this(FileSource.path(IOUtils.downloadImage(source)));
  }

  MatBackedImage(final FileSource source) throws IOException {
    final String path = source.getResource();
    this.mat = imread(path);
  }

  MatBackedImage(final int[] data, final int width, final int height) {
    final Mat originalMat = new Mat(height, width, CvType.CV_8UC4);
    final byte[] byteData = new byte[data.length * 4];
    for (int i = 0; i < data.length; i++) {
      final int pixel = data[i];
      final int idx = i * 4;
      byteData[idx] = (byte) (pixel & 0xFF);
      byteData[idx + 1] = (byte) ((pixel >> 8) & 0xFF);
      byteData[idx + 2] = (byte) ((pixel >> 16) & 0xFF);
      byteData[idx + 3] = (byte) 0xFF;
    }
    originalMat.put(0, 0, byteData);
    this.mat = originalMat;
  }

  MatBackedImage(final BufferedImage image) {
    try (final OpenCVFrameConverter.ToMat converter = new OpenCVFrameConverter.ToMat()) {
      this.mat = converter.convertToOrgOpenCvCoreMat(Java2DFrameUtils.toFrame(image));
    }
  }

  MatBackedImage(final Mat mat) {
    this.mat = mat;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public BufferedImage toBufferedImage() {
    try (final OpenCVFrameConverter.ToMat converter = new OpenCVFrameConverter.ToMat()) {
      final Frame frame = converter.convert(this.mat);
      return Java2DFrameUtils.toBufferedImage(frame);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void resize(final int newWidth, final int newHeight) {
    final Mat resizedMat = new Mat();
    Imgproc.resize(this.mat, resizedMat, new Size(newWidth, newHeight));
    this.mat.release();
    this.mat.assignTo(resizedMat);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void toGrayscale() {
    final Mat grayMat = new Mat();
    Imgproc.cvtColor(this.mat, grayMat, Imgproc.COLOR_BGR2GRAY);
    this.mat.release();
    this.mat.assignTo(grayMat);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void rotate90Clockwise() {
    final Mat rotatedMat = new Mat();
    Core.rotate(this.mat, rotatedMat, Core.ROTATE_90_CLOCKWISE);
    this.mat.release();
    this.mat.assignTo(rotatedMat);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void rotate90CounterClockwise() {
    final Mat rotatedMat = new Mat();
    Core.rotate(this.mat, rotatedMat, Core.ROTATE_90_COUNTERCLOCKWISE);
    this.mat.release();
    this.mat.assignTo(rotatedMat);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void flipHorizontally() {
    final Mat flippedMat = new Mat();
    Core.flip(this.mat, flippedMat, 1);
    this.mat.release();
    this.mat.assignTo(flippedMat);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void flipVertically() {
    final Mat flippedMat = new Mat();
    Core.flip(this.mat, flippedMat, 0);
    this.mat.release();
    this.mat.assignTo(flippedMat);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void crop(final int x, final int y, final int width, final int height) {
    final Rect roi = new Rect(x, y, width, height);
    final Mat croppedMat = new Mat(this.mat, roi);
    this.mat.release();
    this.mat.assignTo(croppedMat);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void invertColors() {
    Core.bitwise_not(this.mat, this.mat);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void adjustBrightness(final double value) {
    Core.add(this.mat, new Scalar(value, value, value), this.mat);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void adjustContrast(final double alpha) {
    this.mat.convertTo(this.mat, -1, alpha, 0);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void toHSV() {
    Imgproc.cvtColor(this.mat, this.mat, Imgproc.COLOR_BGR2HSV);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void toRGB() {
    Imgproc.cvtColor(this.mat, this.mat, Imgproc.COLOR_HSV2BGR);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyGaussianBlur(final int kernelSize) {
    Imgproc.GaussianBlur(this.mat, this.mat, new Size(kernelSize, kernelSize), 0);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyMedianBlur(final int kernelSize) {
    Imgproc.medianBlur(this.mat, this.mat, kernelSize);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyBilateralFilter(final int diameter, final double sigmaColor, final double sigmaSpace) {
    Imgproc.bilateralFilter(this.mat, this.mat, diameter, sigmaColor, sigmaSpace);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyCannyEdgeDetection(final double threshold1, final double threshold2) {
    Imgproc.Canny(this.mat, this.mat, threshold1, threshold2);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyErosion(final int kernelSize) {
    final Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(kernelSize, kernelSize));
    Imgproc.erode(this.mat, this.mat, kernel);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyDilation(final int kernelSize) {
    final Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(kernelSize, kernelSize));
    Imgproc.dilate(this.mat, this.mat, kernel);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applySobelFilter(final int dx, final int dy, final int kernelSize) {
    Imgproc.Sobel(this.mat, this.mat, CvType.CV_16S, dx, dy, kernelSize);
    Core.convertScaleAbs(this.mat, this.mat);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyLaplacianFilter(final int kernelSize) {
    Imgproc.Laplacian(this.mat, this.mat, CvType.CV_16S, kernelSize);
    Core.convertScaleAbs(this.mat, this.mat);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyThreshold(final double threshold, final double maxVal, final int type) {
    Imgproc.threshold(this.mat, this.mat, threshold, maxVal, type);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyAdaptiveThreshold(
    final double maxVal,
    final int adaptiveMethod,
    final int thresholdType,
    final int blockSize,
    final double C
  ) {
    Imgproc.adaptiveThreshold(this.mat, this.mat, maxVal, adaptiveMethod, thresholdType, blockSize, C);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyHistogramEqualization() {
    if (this.mat.channels() == 1) {
      Imgproc.equalizeHist(this.mat, this.mat);
    } else {
      throw new UnsupportedOperationException("Histogram equalization requires a grayscale image.");
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyCLAHE(final double clipLimit, final int tileGridSize) {
    final CLAHE clahe = Imgproc.createCLAHE(clipLimit, new Size(tileGridSize, tileGridSize));
    if (this.mat.channels() == 1) {
      clahe.apply(this.mat, this.mat);
    } else {
      final Mat lab = new Mat();
      Imgproc.cvtColor(this.mat, lab, Imgproc.COLOR_BGR2Lab);
      final List<Mat> labChannels = new ArrayList<>();
      Core.split(lab, labChannels);
      clahe.apply(labChannels.get(0), labChannels.get(0));
      Core.merge(labChannels, lab);
      Imgproc.cvtColor(lab, this.mat, Imgproc.COLOR_Lab2BGR);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyEdgePreservingFilter(final int flags, final float sigmaS, final float sigmaR) {
    Photo.edgePreservingFilter(this.mat, this.mat, flags, sigmaS, sigmaR);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void addScalar(final double value) {
    Core.add(this.mat, new Scalar(value, value, value), this.mat);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void multiplyByScalar(final double value) {
    Core.multiply(this.mat, new Scalar(value, value, value), this.mat);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void bitwiseAnd(final MatBackedImage other) {
    Core.bitwise_and(this.mat, other.mat, this.mat);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void bitwiseOr(final MatBackedImage other) {
    Core.bitwise_or(this.mat, other.mat, this.mat);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void bitwiseXor(final MatBackedImage other) {
    Core.bitwise_xor(this.mat, other.mat, this.mat);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void normalize(final double alpha, final double beta) {
    Core.normalize(this.mat, this.mat, alpha, beta, Core.NORM_MINMAX);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void transpose() {
    Core.transpose(this.mat, this.mat);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public double determinant() {
    return Core.determinant(this.mat);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public List<MatBackedImage> splitChannels() {
    final List<Mat> channels = new ArrayList<>();
    Core.split(this.mat, channels);
    return channels.stream().map(MatBackedImage::new).collect(Collectors.toList());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void mergeChannels(final List<MatBackedImage> channels) {
    Core.merge(channels.stream().map(MatBackedImage::getMat).collect(Collectors.toList()), this.mat);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setPixelValue(final int x, final int y, final double[] value) {
    this.mat.put(y, x, value);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public double[] getPixelValue(final int x, final int y) {
    return this.mat.get(y, x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public MatBackedImage extractSubmatrix(final int x, final int y, final int width, final int height) {
    final Mat submat = this.mat.submat(new Rect(x, y, width, height));
    return new MatBackedImage(submat);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void replaceSubmatrix(final int x, final int y, final MatBackedImage mat) {
    final Mat submatrix = mat.getMat();
    submatrix.copyTo(this.mat.submat(new Rect(x, y, submatrix.cols(), submatrix.rows())));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getMatrixType() {
    return this.mat.type();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getWidth() {
    return this.mat.cols();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getHeight() {
    return this.mat.rows();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public MatBackedImage cloneMatrix() {
    return new MatBackedImage(this.mat.clone());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isEmpty() {
    return this.mat.empty();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setRegionToScalar(final int x, final int y, final int width, final int height, final double[] scalarValue) {
    final Rect region = new Rect(x, y, width, height);
    final Mat submat = this.mat.submat(region);
    submat.setTo(new Scalar(scalarValue));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getMatrixDepth() {
    return this.mat.depth();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int countNonZeroPixels() {
    return Core.countNonZero(this.mat);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void flipBothAxes() {
    Core.flip(this.mat, this.mat, -1);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public byte[] getRawData() {
    final byte[] data = new byte[(int) (this.mat.total() * this.mat.elemSize())];
    this.mat.get(0, 0, data);
    return data;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setRawData(final byte[] data) {
    if (data.length != this.mat.total() * this.mat.elemSize()) {
      throw new IllegalArgumentException("Data size does not match matrix size.");
    }
    this.mat.put(0, 0, data);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean isContinuous() {
    return this.mat.isContinuous();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getElementSize() {
    return (int) this.mat.elemSize();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void zeroOutMatrix() {
    this.mat.setTo(new Scalar(0));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setToIdentity() {
    if (this.mat.rows() != this.mat.cols()) {
      throw new UnsupportedOperationException("Matrix must be square to set to identity.");
    }
    Core.setIdentity(this.mat);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void release() {
    this.mat.release();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int[] getAllPixels() {
    final int width = this.mat.cols();
    final int height = this.mat.rows();
    final int[] pixels = new int[width * height];
    final byte[] byteData = new byte[(int) (this.mat.total() * this.mat.elemSize())];
    this.mat.get(0, 0, byteData);
    for (int i = 0; i < pixels.length; i++) {
      final int idx = i * 4;
      final int blue = byteData[idx] & 0xFF;
      final int green = byteData[idx + 1] & 0xFF;
      final int red = byteData[idx + 2] & 0xFF;
      final int alpha = byteData[idx + 3] & 0xFF;
      pixels[i] = (alpha << 24) | (red << 16) | (green << 8) | blue;
    }
    return pixels;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void toBinary(final double threshold) {
    Imgproc.threshold(this.mat, this.mat, threshold, 255, Imgproc.THRESH_BINARY);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyMorphologicalOpening(final int kernelSize) {
    final Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(kernelSize, kernelSize));
    Imgproc.morphologyEx(this.mat, this.mat, Imgproc.MORPH_OPEN, kernel);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyMorphologicalClosing(final int kernelSize) {
    final Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(kernelSize, kernelSize));
    Imgproc.morphologyEx(this.mat, this.mat, Imgproc.MORPH_CLOSE, kernel);
  }

  Mat getMat() {
    return this.mat;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyPerspectiveTransform(final MatBackedImage transformMatrix, final int width, final int height) {
    final Mat transformedMat = new Mat();
    Imgproc.warpPerspective(this.mat, transformedMat, transformMatrix.getMat(), new Size(width, height));
    this.mat.release();
    this.mat.assignTo(transformedMat);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void drawRectangle(final int x, final int y, final int width, final int height, final double[] colorScalar, final int thickness) {
    Imgproc.rectangle(this.mat, new Point(x, y), new Point((double) x + width, (double) y + height), new Scalar(colorScalar), thickness);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void drawCircle(final int centerX, final int centerY, final int radius, final double[] scalarColor, final int thickness) {
    Imgproc.circle(this.mat, new Point(centerX, centerY), radius, new Scalar(scalarColor), thickness);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void drawLine(
    final int startX,
    final int startY,
    final int endX,
    final int endY,
    final double[] colorScalar,
    final int thickness
  ) {
    Imgproc.line(this.mat, new Point(startX, startY), new Point(endX, endY), new Scalar(colorScalar), thickness);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void drawEllipse(
    final int centerX,
    final int centerY,
    final int axisX,
    final int axisY,
    final int angle,
    final int startAngle,
    final int endAngle,
    final double[] colorScalar,
    final int thickness
  ) {
    Imgproc.ellipse(
      this.mat,
      new Point(centerX, centerY),
      new Size(axisX, axisY),
      angle,
      startAngle,
      endAngle,
      new Scalar(colorScalar),
      thickness
    );
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void drawPolygon(
    final List<me.brandonli.mcav.utils.immutable.Point> points,
    final boolean isClosed,
    final double[] scalarColor,
    final int thickness
  ) {
    final MatOfPoint matOfPoint = new MatOfPoint();
    matOfPoint.fromList(points.stream().map(p -> new Point(p.getX(), p.getY())).collect(Collectors.toList()));
    final List<MatOfPoint> contours = new ArrayList<>();
    contours.add(matOfPoint);
    Imgproc.polylines(this.mat, contours, isClosed, new Scalar(scalarColor), thickness);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void fillPolygon(final List<me.brandonli.mcav.utils.immutable.Point> points, final double[] scalarColor) {
    final MatOfPoint matOfPoint = new MatOfPoint();
    matOfPoint.fromList(points.stream().map(p -> new Point(p.getX(), p.getY())).collect(Collectors.toList()));
    final List<MatOfPoint> contours = new ArrayList<>();
    contours.add(matOfPoint);
    Imgproc.fillPoly(this.mat, contours, new Scalar(scalarColor));
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void drawText(
    final String text,
    final int x,
    final int y,
    final int fontFace,
    final double fontScale,
    final double[] scalarColor,
    final int thickness
  ) {
    Imgproc.putText(this.mat, text, new Point(x, y), fontFace, fontScale, new Scalar(scalarColor), thickness);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public double[] getTextSize(final String text, final int fontFace, final double fontScale, final int thickness) {
    final int[] baseLine = new int[1];
    final Size textSize = Imgproc.getTextSize(text, fontFace, fontScale, thickness, baseLine);
    return new double[] { textSize.width, textSize.height, textSize.area() };
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void overlayImage(final MatBackedImage overlay, final int x, final int y) {
    final Mat overlayMat = overlay.mat;
    final Rect roi = new Rect(x, y, overlayMat.cols(), overlayMat.rows());
    final Mat submat = this.mat.submat(roi);
    Core.addWeighted(submat, 1.0, overlayMat, 1.0, 0.0, submat);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void blendWith(final MatBackedImage other, final double alpha) {
    if (this.mat.size().equals(other.mat.size())) {
      Core.addWeighted(this.mat, alpha, other.mat, 1.0 - alpha, 0.0, this.mat);
    } else {
      throw new IllegalArgumentException("Images must have the same size to blend.");
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyScharrEdgeDetection(final int dx, final int dy) {
    final Mat scharr = new Mat();
    Imgproc.Scharr(this.mat, scharr, CvType.CV_16S, dx, dy);
    Core.convertScaleAbs(scharr, this.mat);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void drawGrid(final int cellWidth, final int cellHeight, final double[] scalarColor, final int thickness) {
    final Scalar color = new Scalar(scalarColor);
    for (int x = 0; x < this.mat.cols(); x += cellWidth) {
      Imgproc.line(this.mat, new Point(x, 0), new Point(x, this.mat.rows()), color, thickness);
    }
    for (int y = 0; y < this.mat.rows(); y += cellHeight) {
      Imgproc.line(this.mat, new Point(0, y), new Point(this.mat.cols(), y), color, thickness);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void resizeKeepingAspectRatio(final int maxWidth, final int maxHeight) {
    final int originalWidth = this.mat.cols();
    final int originalHeight = this.mat.rows();
    final double aspectRatio = (double) originalWidth / originalHeight;
    int newWidth = maxWidth;
    int newHeight = (int) (maxWidth / aspectRatio);
    if (newHeight > maxHeight) {
      newHeight = maxHeight;
      newWidth = (int) (maxHeight * aspectRatio);
    }
    this.resize(newWidth, newHeight);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void detectAndDrawFaces(final String faceCascadePath, final double[] scalarColor, final int thickness) {
    final CascadeClassifier faceCascade = new CascadeClassifier(faceCascadePath);
    final MatOfRect faces = new MatOfRect();
    faceCascade.detectMultiScale(this.mat, faces);
    for (final Rect face : faces.toArray()) {
      Imgproc.rectangle(this.mat, face.tl(), face.br(), new Scalar(scalarColor), thickness);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void detectAndDrawObjects(
    final String modelPath,
    final String configPath,
    final String classesFilePath,
    final double confThreshold,
    final double[] scalarColor,
    final int thickness
  ) {
    final List<String> classNames = new ArrayList<>();
    try (final BufferedReader br = new BufferedReader(new FileReader(classesFilePath))) {
      String line;
      while ((line = br.readLine()) != null) {
        classNames.add(line);
      }
    } catch (final IOException e) {
      throw new AssertionError("Error reading classes file", e);
    }
    final Net net = Dnn.readNetFromDarknet(configPath, modelPath);
    final Mat blob = Dnn.blobFromImage(this.mat, 1 / 255.0, new Size(416, 416), new Scalar(0, 0, 0), true, false);
    net.setInput(blob);
    final List<Mat> outputs = new ArrayList<>();
    final List<String> outNames = net.getUnconnectedOutLayersNames();
    net.forward(outputs, outNames);
    for (final Mat output : outputs) {
      for (int i = 0; i < output.rows(); i++) {
        final Mat row = output.row(i);
        final Mat scores = row.colRange(5, row.cols());
        final Core.MinMaxLocResult result = Core.minMaxLoc(scores);
        final float confidence = (float) result.maxVal;
        if (confidence > confThreshold) {
          final int classId = (int) result.maxLoc.x;
          final int centerX = (int) (row.get(0, 0)[0] * this.mat.cols());
          final int centerY = (int) (row.get(0, 1)[0] * this.mat.rows());
          final int width = (int) (row.get(0, 2)[0] * this.mat.cols());
          final int height = (int) (row.get(0, 3)[0] * this.mat.rows());
          final int x = centerX - width / 2;
          final int y = centerY - height / 2;
          final Scalar color = new Scalar(scalarColor);
          Imgproc.rectangle(this.mat, new Point(x, y), new Point((double) x + width, (double) y + height), color, thickness);
          Imgproc.putText(this.mat, classNames.get(classId), new Point(x, (double) y - 10), Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, color, 1);
        }
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyColorMap(final int colorMap) {
    final Mat colored = new Mat();
    Imgproc.applyColorMap(this.mat, colored, colorMap);
    this.mat.release();
    this.mat.assignTo(colored);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void addColorTint(final double[] tint, final double alpha) {
    final Mat tintedMat = new Mat(this.mat.size(), this.mat.type(), new Scalar(tint));
    Core.addWeighted(this.mat, 1.0 - alpha, tintedMat, alpha, 0.0, this.mat);
    tintedMat.release();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void applyCustomKernel(final float[] kernelData, final int kernelRows, final int kernelCols) {
    final Mat kernel = new Mat(kernelRows, kernelCols, CvType.CV_32F);
    kernel.put(0, 0, kernelData);
    final Mat dst = new Mat();
    Imgproc.filter2D(this.mat, dst, -1, kernel);
    this.mat.release();
    this.mat.assignTo(dst);
    kernel.release();
  }
}

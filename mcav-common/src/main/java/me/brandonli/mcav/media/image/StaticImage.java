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

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import me.brandonli.mcav.media.source.FileSource;
import me.brandonli.mcav.media.source.UriSource;
import me.brandonli.mcav.utils.immutable.Point;

/**
 * The StaticImage interface provides a suite of methods for handling and manipulating static images.
 * It includes capabilities for loading images from various sources, applying transformations,
 * performing image processing operations, and interacting with images at a pixel level.
 * This powerful interface is designed to work with image data in different formats and integrate
 * seamlessly with OpenCV's Mat data structure.
 */
public interface StaticImage extends Image {
  /**
   * Creates a StaticImage instance from a byte array.
   *
   * @param data the byte array representing the image data
   * @return a StaticImage instance
   */
  static StaticImage bytes(final byte[] data) {
    return new MatBackedImage(data);
  }

  /**
   * Creates a StaticImage instance from a URI.
   *
   * @param source the URI source representing the image
   * @return a StaticImage instance
   * @throws IOException if an error occurs while reading the URI
   */
  static StaticImage uri(final UriSource source) throws IOException {
    return new MatBackedImage(source);
  }

  /**
   * Creates a StaticImage instance from a file path.
   *
   * @param path the file path representing the image
   * @return a StaticImage instance
   * @throws IOException if an error occurs while reading the file
   */
  static StaticImage path(final FileSource path) throws IOException {
    return new MatBackedImage(path);
  }

  static StaticImage buffer(final int[] data, final int width, final int height) {
    return new MatBackedImage(data, width, height);
  }

  /**
   * Creates a StaticImage instance from a BufferedImage.
   *
   * @param image the BufferedImage representing the image
   * @return a StaticImage instance
   */
  static StaticImage image(final BufferedImage image) {
    return new MatBackedImage(image);
  }

  /**
   * Constructs a BufferedImage from the current image.
   *
   * @return a BufferedImage representation of the current image
   */
  BufferedImage toBufferedImage();

  /**
   * Resizes the image to the specified width and height.
   *
   * @param newWidth  the new width of the image
   * @param newHeight the new height of the image
   */
  void resize(int newWidth, int newHeight);

  /**
   * Converts the image to grayscale.
   */
  void toGrayscale();

  /**
   * Rotates the current StaticImage instance by 90 degrees in a clockwise direction.
   * <p>
   * This operation modifies the pixel arrangement of the image to achieve a 90-degree clockwise
   * rotation. The dimensions of the image will be swapped; the original width will become the new height,
   * and the original height will become the new width. The image data will be transformed
   * accordingly to reflect the rotation effect.
   * <p>
   * This method does not require any parameters and directly alters the internal representation
   * of the image. Ensure that all required operations are performed on the unrotated image
   * before invoking this method if necessary.
   * <p>
   * This method is generally used for reorienting images, performing transformations,
   * or preparing specific layouts where a rotated image is required.
   */
  void rotate90Clockwise();

  /**
   * Rotates the current image 90 degrees counterclockwise.
   * <p>
   * This method modifies the current image in place, altering its orientation
   * by rotating it 90 degrees in a counterclockwise direction. The width and
   * height dimensions of the image will be swapped as a result of this rotation.
   */
  void rotate90CounterClockwise();

  /**
   * Flips the current image horizontally.
   * <p>
   * This operation mirrors the image along the vertical axis, effectively
   * inverting the left and right sides of the image while preserving
   * its dimensions and orientation.
   */
  void flipHorizontally();

  /**
   * Flips the graphical representation of an image, object, or data structure
   * vertically. This transformation mirrors the content along the horizontal axis,
   * inverting the top and bottom portions.
   * <p>
   * This method modifies the original state by applying the vertical flip operation,
   * ensuring that the top edge becomes the bottom edge and vice versa.
   */
  void flipVertically();

  /**
   * Crops a rectangular region from the image based on the specified coordinates and dimensions.
   *
   * @param x      the x-coordinate of the top-left corner of the cropping rectangle
   * @param y      the y-coordinate of the top-left corner of the cropping rectangle
   * @param width  the width of the cropping rectangle
   * @param height the height of the cropping rectangle
   */
  void crop(int x, int y, int width, int height);

  /**
   * Inverts the colors of the current image.
   * <p>
   * This method transforms the image such that each pixel's value is replaced with
   * its inverse across all color channels. For instance, in an RGB image, each color
   * channel value will be inverted to its complementary value. This effect results
   * in an image that appears as a photographic negative of the original.
   */
  void invertColors();

  /**
   * Adjusts the brightness of an image or display to the specified level.
   *
   * @param value the brightness level to set, where positive values increase brightness
   *              and negative values decrease it
   */
  void adjustBrightness(double value);

  /**
   * Adjusts the contrast of the image.
   *
   * @param alpha the contrast adjustment factor. A value greater than 1 increases contrast,
   *              a value between 0 and 1 decreases contrast, and a value of 1 leaves the contrast unchanged.
   */
  void adjustContrast(double alpha);

  /**
   * Converts the current image representation into the HSV (Hue, Saturation, Value) color space.
   * This process modifies the image data to be expressed in terms of hue, saturation, and brightness,
   * which may be useful for certain types of image analysis or processing.
   */
  void toHSV();

  /**
   * Converts the image to the RGB color space.
   * If the current image is not in the RGB format, this method transforms it
   * into the RGB color representation. It ensures that the resulting image
   * adheres to the standard RGB channel ordering.
   */
  void toRGB();

  /**
   * Applies a Gaussian blur to the image using the specified kernel size.
   * This method smoothens the image by reducing image noise and detail.
   *
   * @param kernelSize the size of the square kernel matrix used for the Gaussian blur operation.
   *                   It must be a positive odd integer (e.g., 3, 5, 7, etc.).
   */
  void applyGaussianBlur(int kernelSize);

  /**
   * Applies a median blur filter to the image using the specified kernel size.
   * The median blur is a non-linear filter used to reduce noise while preserving edges.
   *
   * @param kernelSize the size of the kernel to be used for the median blur.
   *                   It must be a positive odd integer (e.g., 3, 5, 7, etc.).
   *                   Larger values result in stronger blurring.
   */
  void applyMedianBlur(int kernelSize);

  /**
   * Applies a bilateral filter to the image. A bilateral filter is a non-linear, edge-preserving, and noise-reducing
   * smoothing filter. It effectively reduces noise while keeping edges sharp.
   *
   * @param diameter   the diameter of each pixel neighborhood used during filtering. A larger value means
   *                   that more surrounding pixels are taken into account for filtering.
   * @param sigmaColor the filter sigma in the color space. A larger value indicates that distant colors
   *                   within the pixel neighborhood will be mixed together, resulting in stronger smoothing.
   * @param sigmaSpace the filter sigma in the coordinate space. A larger value means that farther pixels
   *                   from the center pixel will influence the result as long as their colors are close enough.
   */
  void applyBilateralFilter(int diameter, double sigmaColor, double sigmaSpace);

  /**
   * Applies the Canny edge detection algorithm to the current image.
   * This process detects edges in the image by calculating the intensity
   * gradient and performs hysteresis thresholding to determine strong
   * and weak edges.
   *
   * @param threshold1 The first threshold for the hysteresis procedure.
   *                   Determines the lower bound for edge linking.
   * @param threshold2 The second threshold for the hysteresis procedure.
   *                   Determines the upper bound for strong edge detection.
   */
  void applyCannyEdgeDetection(double threshold1, double threshold2);

  /**
   * Applies morphological erosion to the image using a specified kernel size. Erosion
   * reduces objects in the image by eroding boundaries of regions, thereby removing noise
   * or small structures from the image.
   *
   * @param kernelSize the size of the square kernel to be used for erosion. A larger
   *                   kernel size results in a more pronounced erosion effect.
   */
  void applyErosion(int kernelSize);

  /**
   * Applies dilation to the image using a specified kernel size. Dilation is a
   * morphological operation that grows the boundaries of objects in the image,
   * commonly used for enhancing image characteristics or reducing noise.
   *
   * @param kernelSize the size of the kernel used for the dilation process.
   *                   Must be a positive odd integer.
   */
  void applyDilation(int kernelSize);

  /**
   * Applies the Sobel filter to the image to detect edges by computing image gradients in the specified directions.
   * The filter uses the given kernel size to adjust the sensitivity and quality of the edge detection.
   *
   * @param dx         the order of the derivative in the x-direction. Common values are 0 or 1.
   * @param dy         the order of the derivative in the y-direction. Common values are 0 or 1.
   * @param kernelSize the size of the kernel to be used. It must be an odd number greater than 1.
   */
  void applySobelFilter(int dx, int dy, int kernelSize);

  /**
   * Applies a Laplacian filter to an image or matrix to detect edges.
   * This method utilizes a kernel of the specified size to compute the second derivatives
   * of the image intensities, enhancing regions with rapid intensity changes.
   *
   * @param kernelSize the size of the Laplacian kernel to be applied. It should
   *                   typically be a positive odd integer greater than 1. A larger
   *                   kernel size may result in smoother edges, while a smaller size
   *                   provides finer edge details.
   */
  void applyLaplacianFilter(int kernelSize);

  /**
   * Applies a fixed-level threshold to the image based on the specified parameters.
   *
   * @param threshold the threshold value used for thresholding
   * @param maxVal    the maximum value to use for specifying intensity for the binary or binary inverse thresholds
   * @param type      the type of threshold operation (e.g., binary, binary inverse, truncate, etc.)
   */
  void applyThreshold(double threshold, double maxVal, int type);

  /**
   * Applies adaptive thresholding to the image using the specified parameters.
   *
   * @param maxVal         the maximum intensity value to use for the thresholded pixels
   * @param adaptiveMethod the adaptive thresholding algorithm to use (e.g., MEAN_C or GAUSSIAN_C)
   * @param thresholdType  the type of thresholding (e.g., BINARY or BINARY_INV)
   * @param blockSize      the size of the area used to calculate the threshold for each pixel (must be odd)
   * @param C              a constant value subtracted from the calculated adaptive threshold
   */
  void applyAdaptiveThreshold(double maxVal, int adaptiveMethod, int thresholdType, int blockSize, double C);

  /**
   * Applies histogram equalization to the image.
   * <p>
   * This method enhances the contrast of the image by redistributing
   * the intensity values of pixels, so that they span a wider range
   * of intensities. It is particularly useful for improving the clarity
   * of images with low dynamic ranges or poor contrast.
   */
  void applyHistogramEqualization();

  /**
   * Applies the Contrast Limited Adaptive Histogram Equalization (CLAHE) algorithm to the image.
   * This method enhances the contrast of an image by applying adaptive histogram equalization
   * with a specified clip limit and tile grid size.
   *
   * @param clipLimit    the threshold for contrast limiting; higher values allow more contrast enhancement
   * @param tileGridSize the size of the grid for dividing the image into tiles; larger sizes result in fewer tiles
   */
  void applyCLAHE(double clipLimit, int tileGridSize);

  /**
   * Applies an edge-preserving filter to an image. This method is typically
   * used in image processing to smooth regions of an image while preserving
   * sharp edges, enhancing the overall image quality.
   *
   * @param flags Specifies the operation mode for the filter. The specific
   *              values and their meaning depend on the implementation context.
   * @param sigmaS The range parameter that controls the spatial extent of the
   *               filter. Larger values result in smoothing over larger regions.
   * @param sigmaR The range parameter that controls the amplitude of the
   *               filter. It determines the degree of smoothing, where higher
   *               values tend to blur the image more.
   */
  void applyEdgePreservingFilter(int flags, float sigmaS, float sigmaR);

  /**
   * Adds a scalar value to each element of the matrix or image.
   *
   * @param value the scalar value to be added to the matrix or image elements
   */
  void addScalar(double value);

  /**
   * Multiplies the current object by the specified scalar value.
   *
   * @param value the scalar value by which the current object will be multiplied
   */
  void multiplyByScalar(double value);

  /**
   * Applies a bitwise AND operation between this image and the specified MatBackedImage.
   *
   * @param other the other MatBackedImage to perform the bitwise AND operation with
   */
  void bitwiseAnd(MatBackedImage other);

  /**
   * Performs a bitwise OR operation between the current image and the specified image.
   * The operation combines the two images at the pixel level, effectively setting each
   * output pixel value to the logical OR of the corresponding pixel values from the two
   * images.
   *
   * @param other the other MatBackedImage to perform the bitwise OR operation with
   */
  void bitwiseOr(MatBackedImage other);

  /**
   * Performs a bitwise XOR operation between the current image and another image.
   * The operation is performed element-wise on the pixel values of the two images.
   *
   * @param other the MatBackedImage instance to XOR with the
   */
  void bitwiseXor(MatBackedImage other);

  /**
   * Normalizes the image data to a specified range.
   *
   * @param alpha the lower bound of the normalization range
   * @param beta  the upper bound of the normalization range
   */
  void normalize(double alpha, double beta);

  /**
   * Transposes the matrix represented by the current instance.
   * The transpose operation flips the matrix over its diagonal,
   * swapping the row and column indices of the elements.
   * <p>
   * This method modifies the original matrix in-place.
   * <p>
   * Preconditions:
   * - The matrix should be a square matrix (number of rows equals number of columns)
   * for in-place transposition.
   * <p>
   * Postconditions:
   * - The matrix is modified such that its rows become columns and columns become rows.
   * <p>
   * Throws:
   * - UnsupportedOperationException if the matrix is not square.
   */
  void transpose();

  /**
   * Computes and returns the determinant of the matrix represented by the image data.
   *
   * @return the determinant value as a double. If the matrix is not square or
   * the determinant cannot be computed, behavior may vary depending
   * on the matrix implementation.
   */
  double determinant();

  /**
   * Splits the current image into its individual color channels.
   *
   * @return a list of MatBackedImage objects where each image represents a single
   * color channel of the original image.
   */
  List<MatBackedImage> splitChannels();

  /**
   * Merges multiple channels into a single image. Each channel provided in the input list is
   * combined to form a complete multi-channel image.
   *
   * @param channels a list of MatBackedImage objects representing individual image channels
   */
  void mergeChannels(List<MatBackedImage> channels);

  /**
   * Sets the value of a pixel at the specified coordinates in an image or matrix.
   *
   * @param x     The x-coordinate of the pixel in the image or matrix.
   * @param y     The y-coordinate of the pixel in the image or matrix.
   * @param value The value to assign to the pixel, represented as an array of doubles.
   */
  void setPixelValue(int x, int y, double[] value);

  /**
   * Retrieves the pixel value at the specified coordinates in the image.
   *
   * @param x the x-coordinate of the pixel
   * @param y the y-coordinate of the pixel
   * @return an array of doubles representing the pixel's value in the image,
   * where the number of elements in the array depends on the image's number of channels.
   * For instance, a grayscale image may return a single value, while a color image may return multiple values (e.g., RGB channels).
   */
  double[] getPixelValue(int x, int y);

  /**
   * Extracts a submatrix from the source matrix starting at the specified coordinates
   * with the specified width and height.
   *
   * @param x the x-coordinate of the top-left corner of the submatrix
   * @param y the y-coordinate of the top-left corner of the submatrix
   * @param width the width of the submatrix to be extracted
   * @param height the height of the submatrix to be extracted
   * @return a MatBackedImage representing the extracted submatrix
   */
  MatBackedImage extractSubmatrix(int x, int y, int width, int height);

  /**
   * Replaces a specific submatrix within the current matrix at a given position.
   *
   * @param x the x-coordinate of the top-left corner where the submatrix will be placed
   * @param y the y-coordinate of the top-left corner where the submatrix will be placed
   * @param mat the submatrix to replace the corresponding section of the main matrix
   */
  void replaceSubmatrix(int x, int y, MatBackedImage mat);

  /**
   * Retrieves the matrix type of the underlying image representation.
   *
   * @return an integer representing the matrix type, typically referring to the predefined types in the image-processing library being used
   */
  int getMatrixType();

  /**
   * Retrieves the width of the image associated with the instance.
   *
   * @return the width of the image in pixels
   */
  int getWidth();

  /**
   * Retrieves the height value.
   *
   * @return the height as an integer
   */
  int getHeight();

  /**
   * Creates and returns a deep copy of the current matrix representation of the image.
   * The cloned matrix is independent of the original matrix, allowing changes to be
   * made to the cloned matrix without affecting the original.
   *
   * @return a new MatBackedImage instance representing a deep copy of the current matrix
   */
  MatBackedImage cloneMatrix();

  /**
   * Checks if the object or structure is empty.
   *
   * @return true if the object or structure is empty, false otherwise
   */
  boolean isEmpty();

  /**
   * Updates a rectangular region of an image or data structure to a specified scalar value.
   *
   * @param x           The x-coordinate of the top-left corner of the region.
   * @param y           The y-coordinate of the top-left corner of the region.
   * @param width       The width of the region.
   * @param height      The height of the region.
   * @param scalarValue An array representing the scalar value to set the region to.
   */
  void setRegionToScalar(int x, int y, int width, int height, double[] scalarValue);

  /**
   * Retrieves the depth of the matrix, which indicates the number of bits
   * used to represent each pixel/channel in the image.
   *
   * @return the depth of the matrix as an integer
   */
  int getMatrixDepth();

  /**
   * Counts the number of non-zero pixels in the image matrix.
   *
   * @return the total count of non-zero pixels in the image
   */
  int countNonZeroPixels();

  /**
   * Flips the image along both the horizontal and vertical axes.
   * This operation effectively rotates the image 180 degrees
   * without changing its size or dimensions.
   */
  void flipBothAxes();

  /**
   * Retrieves the raw byte array representation of the image data.
   *
   * @return a byte array containing the raw image data
   */
  byte[] getRawData();

  /**
   * Sets the raw data to the specified byte array.
   *
   * @param data the byte array containing the raw data to be set
   */
  void setRawData(byte[] data);

  /**
   * Evaluates if the sequence or process is continuous without interruptions or gaps.
   *
   * @return true if the sequence or process is continuous, false otherwise
   */
  boolean isContinuous();

  /**
   * Retrieves the size of an individual element in the matrix or image data structure.
   *
   * @return the size, in bytes, of a single element.
   */
  int getElementSize();

  /**
   * Modifies a matrix such that if an element in the matrix is zero,
   * its entire row and column are set to zero.
   * <p>
   * This method operates on the matrix in-place. The transformation
   * applies to the matrix by identifying rows and columns containing
   * zeroes and subsequently updating them.
   * <p>
   * It is expected that the matrix is represented as a
   */
  void zeroOutMatrix();

  /**
   * Sets the current matrix to the identity matrix.
   * <p>
   * The identity matrix is a square matrix in which all the elements of the principal
   * diagonal are ones, and all other elements are zeros.
   */
  void setToIdentity();

  /**
   * Releases any resources associated with the current StaticImage instance.
   * This method should be called to free up memory and resources when the instance
   * is no longer needed.
   */
  void release();

  /**
   * Retrieves all the pixels from an image as an array of integers.
   *
   * @return an array of integers representing the pixel data of the image
   */
  int[] getAllPixels();

  /**
   * Converts a numerical representation to its binary equivalent
   * based on a specified threshold.
   *
   * @param threshold the numerical threshold used to determine
   *                  the binary conversion logic
   */
  void toBinary(double threshold);

  /**
   * Applies a morphological opening operation to the image using the specified kernel size.
   * Morphological opening is a combination of erosion followed by dilation,
   * which is typically used to remove small objects or noise from the image's foreground.
   *
   * @param kernelSize the size of the structuring element (kernel) used for the operation.
   *                   It should be a positive odd integer.
   */
  void applyMorphologicalOpening(int kernelSize);

  /**
   * Applies a morphological closing operation on the image.
   * Morphological closing involves a dilation operation followed by an erosion operation
   * and is typically used to fill small holes or gaps in the image.
   *
   * @param kernelSize the size of the structuring element (kernel) used for the morphological operation;
   *                   it
   */
  void applyMorphologicalClosing(int kernelSize);

  /**
   * Applies a perspective transform to the given matrix and modifies it
   * according to the specified dimensions.
   *
   * @param transformMatrix the matrix containing transformation data, which will
   *                        be used to apply the perspective transformation
   * @param width           the target width of the resulting transformed image
   * @param height          the target height of the resulting transformed image
   */
  void applyPerspectiveTransform(MatBackedImage transformMatrix, int width, int height);

  /**
   * Draws a rectangle on the canvas with specified position, dimensions, color, and thickness.
   *
   * @param x           the x-coordinate of the top-left corner of the rectangle
   * @param y           the y-coordinate of the top-left corner of the rectangle
   * @param width       the width of the rectangle in pixels
   * @param height      the height of the rectangle in pixels
   * @param colorScalar the color of the rectangle in an array representing RGBA values
   * @param thickness   the thickness of the rectangle's border in pixels
   */
  void drawRectangle(int x, int y, int width, int height, double[] colorScalar, int thickness);

  /**
   * Draws a circle on the image.
   *
   * @param centerX     the x-coordinate of the center of the circle
   * @param centerY     the y-coordinate of the center of the circle
   * @param radius      the radius of the circle
   * @param scalarColor the color of the circle given in scalar format (e.g., [B, G, R] for a colored image)
   * @param thickness   the thickness of the circle's outline; if
   */
  void drawCircle(int centerX, int centerY, int radius, double[] scalarColor, int thickness);

  /**
   * Draws a line between two points with specified color and thickness.
   *
   * @param startX       The x-coordinate of the starting point of the line.
   * @param startY       The y-coordinate of the starting point of the line.
   * @param endX         The x-coordinate of the ending point of the line.
   * @param endY         The y-coordinate of the ending point of the line.
   * @param colorScalar  An array representing the color of the line, typically in a format such as RGB or RGBA.
   * @param thickness    The thickness of the line.
   */
  void drawLine(int startX, int startY, int endX, int endY, double[] colorScalar, int thickness);

  /**
   * Draws an ellipse on the image with the specified parameters.
   *
   * @param centerX     the x-coordinate of the ellipse center
   * @param centerY     the y-coordinate of the ellipse center
   * @param axisX       the length of the major (horizontal) axis of the ellipse
   * @param axisY       the length of the minor (vertical) axis of the ellipse
   * @param angle       the rotation angle of the ellipse in degrees
   * @param startAngle  the starting angle of the ellipse arc in degrees (measured from the horizontal axis)
   * @param endAngle    the ending angle of the ellipse arc in degrees (measured from the horizontal axis)
   * @param colorScalar the BGR(A) color of the ellipse, represented as an array of doubles
   * @param thickness   the thickness of the ellipse boundary; use a negative value to fill the ellipse
   */
  void drawEllipse(
    int centerX,
    int centerY,
    int axisX,
    int axisY,
    int angle,
    int startAngle,
    int endAngle,
    double[] colorScalar,
    int thickness
  );

  /**
   * Draws a polygon based on the provided list of points.
   *
   * @param points       the list of points representing the vertices of the polygon
   * @param isClosed     a boolean indicating whether the polygon should be closed by connecting
   *                     the last point back to the first
   * @param scalarColor  an array of doubles representing the color of the polygon in an appropriate
   *                     format (e.g., RGB or similar, depending on implementation)
   * @param thickness    the thickness of the polygon's edges
   */
  void drawPolygon(List<Point> points, boolean isClosed, double[] scalarColor, int thickness);

  /**
   * Fills a polygon defined by a list of points with the specified color.
   *
   * @param points      a list of points representing the vertices of the polygon
   * @param scalarColor an array representing the color to fill the polygon,
   *                    typically in the format [B, G, R, (optional) A] where each
   *                    component is a double
   */
  void fillPolygon(List<Point> points, double[] scalarColor);

  /**
   * Draws the specified text string on an image at the given location with the specified font attributes.
   *
   * @param text        The text string to be drawn.
   * @param x           The x-coordinate of the bottom-left corner of the text string in the image.
   * @param y           The y-coordinate of the bottom-left corner of the text string in the image.
   * @param fontFace    The font face identifier for drawing the text.
   * @param fontScale   The scale factor that is multiplied by the base font size.
   * @param scalarColor An array representing the color of the text in the format [B, G, R], where B, G, and R are values between 0 and 255.
   * @param thickness   The thickness of the lines used to draw the text.
   */
  void drawText(String text, int x, int y, int fontFace, double fontScale, double[] scalarColor, int thickness);

  /**
   * Calculates and returns the size of a text string when rendered with the specified font face, scale, and thickness.
   *
   * @param text      the text string to measure
   * @param fontFace  the font face to be used for rendering the text
   * @param fontScale the scale factor for the font size
   * @param thickness the thickness of the text
   * @return an array containing the width and height of the text in the specified font properties
   */
  double[] getTextSize(String text, int fontFace, double fontScale, int thickness);

  /**
   * Overlays an image on top of the current image at the specified coordinates.
   *
   * @param overlay the image to be overlaid on the current image
   * @param x       the x-coordinate where the top-left corner of the overlay image will be placed
   * @param y       the y-coordinate where the top-left corner of the overlay image will be placed
   */
  void overlayImage(MatBackedImage overlay, int x, int y);

  /**
   * Blends the current image with another image using the specified blending factor (alpha).
   *
   * @param other the other MatBackedImage to blend with. This image must be of the same dimensions as the current image.
   * @param alpha the blending factor, where 0.0 represents full visibility of the current image
   *              and 1.0 represents full visibility of the other image. Must be a value between 0.0 and 1.0.
   */
  void blendWith(MatBackedImage other, double alpha);

  /**
   * Applies Scharr edge detection to an image based on the specified order of derivatives.
   * Scharr is used to calculate the gradient of the image in the x and y directions.
   *
   * @param dx The order of the derivative x. Typically 1 for edge detection along the x-axis.
   * @param dy The order of the derivative y. Typically 0 for edge detection along the x-axis
   *           or 1 for detection along the y-axis.
   */
  void applyScharrEdgeDetection(int dx, int dy);

  /**
   * Draws a grid with the specified cell dimensions, color scaling, and line thickness.
   *
   * @param cellWidth   the width of each cell in the grid
   * @param cellHeight  the height of each cell in the grid
   * @param scalarColor an array representing the color scaling values for the grid
   * @param thickness   the thickness of the lines forming the grid
   */
  void drawGrid(int cellWidth, int cellHeight, double[] scalarColor, int thickness);

  /**
   * Resizes the image to fit within the specified maximum width and height
   * while preserving the original aspect ratio.
   *
   * @param maxWidth  the maximum width of the resized image
   * @param maxHeight the maximum height of the resized image
   */
  void resizeKeepingAspectRatio(int maxWidth, int maxHeight);

  /**
   * Detects faces in the image using a specified face cascade file and draws
   * rectangles around detected faces with the specified color and thickness.
   *
   * @param faceCascadePath the file path to the pre-trained face detection cascade model
   * @param scalarColor     an array representing the color of the rectangle to be drawn
   *                        (e.g., [R, G, B] values for RGB color)
   * @param thickness       the thickness of the rectangle's border in pixels
   */
  void detectAndDrawFaces(String faceCascadePath, double[] scalarColor, int thickness);

  /**
   * Detects objects in an image using a specified deep learning model and draws bounding boxes on detected objects.
   *
   * @param modelPath       The file path to the pre-trained deep learning model.
   * @param configPath      The file path to the configuration file for the deep learning model.
   * @param classesFilePath The file path to the file containing class labels for the model.
   * @param confThreshold   The confidence threshold for detecting objects. Only detections with a confidence higher than this value will be considered.
   * @param scalarColor     An array representing the color to be used for the bounding boxes, defined in BGR format.
   * @param thickness       The thickness of the lines used for drawing the bounding boxes.
   */
  void detectAndDrawObjects(
    String modelPath,
    String configPath,
    String classesFilePath,
    double confThreshold,
    double[] scalarColor,
    int thickness
  );

  /**
   * Applies a color map to modify the visual representation of an image or data.
   *
   * @param colorMap the integer representing the color map to apply. This parameter
   *                 determines the mapping of colors for visualization purposes.
   */
  void applyColorMap(int colorMap);

  /**
   * Applies a color tint to an element with a specified opacity.
   *
   * @param tint An array representing the color tint in RGBA format, where each component is a value between 0 and 1.
   * @param alpha The opacity level of the tint, represented as a value between 0 (completely transparent) and 1 (completely opaque).
   */
  void addColorTint(double[] tint, double alpha);

  /**
   * Applies a custom kernel to modify an image or matrix.
   * The kernel is defined by its data and dimensions, and it is applied
   * to the target object to perform operations such as convolution or filtering.
   *
   * @param kernelData The array containing the kernel values. This is a flat array
   *                   where the values are stored in row-major order.
   * @param kernelRows The number of rows in the kernel matrix.
   * @param kernelCols The number of columns in the kernel matrix.
   */
  void applyCustomKernel(float[] kernelData, int kernelRows, int kernelCols);
}

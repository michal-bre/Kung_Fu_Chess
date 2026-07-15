package org.example.view.imglib;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Vendored, unmodified-in-behavior copy of the "IMG" image-utility class
 * supplied in CTD26-main/java/src/main/java/Img.java (only a package
 * declaration was added so it sits inside org.example's source tree).
 *
 * Per the design brief: the View layer must render using THIS library
 * rather than a third-party design/UI library. Nothing in this file is
 * project-specific - it is a thin, dependency-free wrapper over
 * javax.imageio/java.awt. All Kung Fu Chess-specific rendering logic lives
 * in org.example.view (e.g. BoardView), which is built on top of this
 * class, not inside it - keeping the vendored library and the
 * application's own view code cleanly separated.
 */
public class Img {

    private BufferedImage img;

    /* ----------- load & optional resize ----------- */
    public Img read(String path,
                    Dimension targetSize,
                    boolean keepAspect,
                    Object interpolation /*ignored*/) {

        try {
            img = ImageIO.read(new File(path));
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot load image: " + path);
        }
        if (img == null) throw new IllegalArgumentException("Unsupported image: " + path);

        if (targetSize != null) {
            int tw = targetSize.width, th = targetSize.height;
            int w = img.getWidth(), h = img.getHeight();

            int nw, nh;
            if (keepAspect) {
                double s = Math.min(tw / (double) w, th / (double) h);
                nw = (int) Math.round(w * s);
                nh = (int) Math.round(h * s);
            } else { nw = tw; nh = th; }

            BufferedImage dst = new BufferedImage(
                    nw, nh,
                    img.getColorModel().hasAlpha()
                            ? BufferedImage.TYPE_INT_ARGB
                            : BufferedImage.TYPE_INT_RGB);

            Graphics2D g = dst.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                               RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, nw, nh, null);
            g.dispose();
            img = dst;
        }
        return this;
    }

    public Img read(String path) { return read(path, null, false, null); }

    /* ----------- construct without loading from disk -----------
     * Purely additive - existing behavior-preserving reasoning for the
     * read()-based constructors above is untouched. These three exist so a
     * caller (e.g. a Renderer) can build/duplicate/wrap an in-memory canvas
     * without needing a file on disk for every frame it draws. */

    /** A fresh, blank canvas of the given size - not loaded from any file. */
    public static Img blank(int width, int height, boolean hasAlpha) {
        Img canvas = new Img();
        canvas.img = new BufferedImage(width, height,
                hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        return canvas;
    }

    /** Wraps an already-in-memory BufferedImage as an Img, without copying it. */
    public static Img wrap(BufferedImage image) {
        Img wrapped = new Img();
        wrapped.img = image;
        return wrapped;
    }

    /** An independent copy of this image - mutating the copy never affects the original. */
    public Img copy() {
        if (img == null) throw new IllegalStateException("Image not loaded.");

        BufferedImage dst = new BufferedImage(img.getWidth(), img.getHeight(), img.getType());
        Graphics2D g = dst.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return wrap(dst);
    }

    /* ----------- draw a flat, optionally translucent rectangle ----------- */
    public void fillRect(int x, int y, int width, int height, Color color) {
        if (img == null) throw new IllegalStateException("Image not loaded.");

        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(x, y, width, height);
        g.dispose();
    }

    /* ----------- draw this image onto another ----------- */
    public void drawOn(Img other, int x, int y) {
        if (img == null || other.img == null)
            throw new IllegalStateException("Both images must be loaded.");

        if (x + img.getWidth()  > other.img.getWidth()
         || y + img.getHeight() > other.img.getHeight())
            throw new IllegalArgumentException("Patch exceeds destination bounds.");

        Graphics2D g = other.img.createGraphics();
        g.setComposite(AlphaComposite.SrcOver);
        g.drawImage(img, x, y, null);
        g.dispose();
    }

    /* ----------- annotate with text ----------- */
    public void putText(String txt, int x, int y, float fontSize,
                        Color color, int thickness /*unused in Java2D*/) {

        if (img == null) throw new IllegalStateException("Image not loaded.");

        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                           RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(color);
        g.setFont(img.getGraphics().getFont().deriveFont(fontSize * 12));
        g.drawString(txt, x, y);
        g.dispose();
    }

    /* ----------- display in a Swing window ----------- */
    public void show() {
        if (img == null) throw new IllegalStateException("Image not loaded.");

        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("Image");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.add(new JLabel(new ImageIcon(img)));
            f.pack();
            f.setLocationRelativeTo(null);
            f.setVisible(true);
        });
    }

    /* ----------- access (optional) ----------- */
    public BufferedImage get() { return img; }
}

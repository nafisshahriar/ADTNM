package gui;

import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import core.DTNHost;
import core.Settings;
import core.SimClock;
import gui.playfield.PlayField;

/**
 * Temporary standalone screenshot harness compiled ONLY to a scratch bytecode
 * directory during documentation build (never modifies core src/).
 */
public class CaptureOneGuiScreenshot extends DTNSimGUI {

    private final double targetSimTimeSec;
    private final File outPng;

    public CaptureOneGuiScreenshot(double targetSimTimeSec, File outPng) {
        this.targetSimTimeSec = targetSimTimeSec;
        this.outPng = outPng;
    }

    @Override
    protected void runSim() {
        String mode = "OFFSCREEN_PLAYFIELD_FALLBACK";
        try {
            if (!GraphicsEnvironment.isHeadless()) {
                Method mStart = DTNSimGUI.class.getDeclaredMethod("startGUI");
                mStart.setAccessible(true);
                mStart.invoke(this);
                setPaused(true);
                mode = "SWING_MAINWINDOW";
            }
            while (SimClock.getTime() < this.targetSimTimeSec && !this.simCancelled) {
                this.world.update();
            }
            if ("SWING_MAINWINDOW".equals(mode)) {
                DTNHost focusHost = this.world.getHosts().size() > 53
                        ? this.world.getHosts().get(53)
                        : this.world.getHosts().get(0);
                SwingUtilities.invokeAndWait(() -> {
                    setFocus(focusHost);
                });
                update(true);
                Thread.sleep(350);
                MainWindow mw = getParentFrame();
                int w = Math.max(1200, mw.getWidth());
                int h = Math.max(800, mw.getHeight());
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                SwingUtilities.invokeAndWait(() -> {
                    mw.setSize(w, h);
                    mw.validate();
                    Graphics2D g2 = img.createGraphics();
                    mw.paintAll(g2);
                    g2.dispose();
                });
                ImageIO.write(img, "png", this.outPng);
            } else {
                Field fField = DTNSimGUI.class.getDeclaredField("field");
                fField.setAccessible(true);
                PlayField pf = (PlayField) fField.get(this);
                if (pf == null) {
                    pf = new PlayField(this.world, this);
                }
                int w = Math.max(1100, pf.getWidth());
                int h = Math.max(760, pf.getHeight());
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2 = img.createGraphics();
                pf.setSize(w, h);
                pf.paint(g2);
                g2.dispose();
                ImageIO.write(img, "png", this.outPng);
            }
            System.out.println("GUI_CAPTURE_MODE=" + mode);
            System.out.println("GUI_CAPTURE_FILE=" + this.outPng.getAbsolutePath());
        } catch (Exception ex) {
            ex.printStackTrace();
            System.exit(2);
        }
        System.exit(0);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: CaptureOneGuiScreenshot <scenarioFile> <targetSimTimeSec> <outPng>");
            System.exit(1);
        }
        Settings.init(args[0]);
        double targetSec = Double.parseDouble(args[1]);
        File outFile = new File(args[2]);
        if (outFile.getParentFile() != null) {
            outFile.getParentFile().mkdirs();
        }
        new CaptureOneGuiScreenshot(targetSec, outFile).start();
    }
}

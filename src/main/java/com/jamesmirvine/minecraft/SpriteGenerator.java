package com.jamesmirvine.minecraft;

import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Util;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.registry.GameRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

// The value here should match an entry in the META-INF/mods.toml file
@Mod("sprite-generator")
public class SpriteGenerator
{
    // Directly reference a log4j logger.
    private static final Logger LOGGER = LogManager.getLogger();
    private static final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss");
    private static final int SIZE = 32;

    private boolean startedOnce = false;
    private float oldZLevel = 0;

    public SpriteGenerator() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public void onRenderEvent(TickEvent.RenderTickEvent event) {
        if (!startedOnce) {
            startedOnce = true;
            bulkRender();
        }
    }

    private void bulkRender() {
        int renderCount = 0;

        LOGGER.info("Gathering all items");
        List<ItemStack> toRender = Lists.newArrayList();
        for (final Item item : GameRegistry.findRegistry(Item.class)) {
            toRender.add(new ItemStack(item));
        }

        final File folder = new File("renders/" + dateFormat.format(new Date()) + "/");
        long lastUpdate = Util.milliTime();

        LOGGER.info("Rendering all " + toRender.size() + " items");
        setUpRenderState();
        for (ItemStack is : toRender) {
            render(is, folder);
            renderCount++;
            if (Util.milliTime() - lastUpdate > 33) {
                tearDownRenderState();
                LOGGER.info("Rendered " + renderCount + " out of " + toRender.size() + " items so far");
                lastUpdate = Util.milliTime();
                setUpRenderState();
            }
        }

        LOGGER.info("Successfully rendered items");

        tearDownRenderState();
    }

    private void setUpRenderState() {
        final Minecraft minecraft = Minecraft.getInstance();

        // Switches from 3D to 2D
        minecraft.mainWindow.loadGUIRenderMatrix(Minecraft.IS_RUNNING_ON_MAC);
        RenderHelper.enableGUIStandardItemLighting();
        /*
         * The GUI scale affects us due to the call to setupOverlayRendering
         * above. As such, we need to counteract this to always get a 512x512
         * render. We could manually switch to orthogonal mode, but it's just
         * more convenient to leverage setupOverlayRendering.
         */
        double scale = SIZE / (16f * minecraft.mainWindow.getGuiScaleFactor());
        GlStateManager.translated(0, 0, -(scale*100));
        GlStateManager.scaled(scale, scale, scale);

        oldZLevel = minecraft.getItemRenderer().zLevel;
        minecraft.getItemRenderer().zLevel = -50;

        GlStateManager.enableRescaleNormal();
        GlStateManager.enableColorMaterial();
        GlStateManager.enableDepthTest();
        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableAlphaTest();
    }

    private void render(final ItemStack itemStack, final File folder) {
        Minecraft minecraft = Minecraft.getInstance();
        String filename = sanitize(itemStack.getItem().getRegistryName().toString());

        GlStateManager.pushMatrix();
        GlStateManager.clearColor(0, 0, 0, 0);
        GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.IS_RUNNING_ON_MAC);
        minecraft.getItemRenderer().renderItemAndEffectIntoGUI(itemStack, 0, 0);
        GlStateManager.popMatrix();

        try {
            /*
             * We need to flip the image over here, because again, GL Y-zero is
             * the bottom, so it's "Y-up". Minecraft's Y-zero is the top, so it's
             * "Y-down". Since readPixels is Y-up, our Y-down render is flipped.
             * It's easier to do this operation on the resulting image than to
             * do it with GL transforms. Not faster, just easier.
             */
            BufferedImage img = createFlipped(readPixels(SIZE, SIZE));

            File f = new File(folder, filename+".png");
            int i = 2;
            while (f.exists()) {
                f = new File(folder, filename+"_"+i+".png");
                i++;
            }
            Files.createParentDirs(f);
            f.createNewFile();
            ImageIO.write(img, "PNG", f);

            LOGGER.info("Successfully rendered: " + f.getPath());
        } catch (Exception ex) {
            LOGGER.error("Failed to render", ex);
        }
    }

    private void tearDownRenderState() {
        GlStateManager.disableLighting();
        GlStateManager.disableColorMaterial();
        GlStateManager.disableDepthTest();
        GlStateManager.disableBlend();

        Minecraft.getInstance().getItemRenderer().zLevel = oldZLevel;
    }

    private BufferedImage readPixels(int width, int height) {
        /*
         * Make sure we're reading from the back buffer, not the front buffer.
         * The front buffer is what is currently on-screen, and is useful for
         * screenshots.
         */
        GL11.glReadBuffer(GL11.GL_BACK);
        // Allocate a native data array to fit our pixels
        ByteBuffer buf = BufferUtils.createByteBuffer(width * height * 4);
        // And finally read the pixel data from the GPU...
        GL11.glReadPixels(0, Minecraft.getInstance().mainWindow.getFramebufferHeight()-height, width, height, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, buf);
        // ...and turn it into a Java object we can do things to.
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[width*height];
        buf.asIntBuffer().get(pixels);
        img.setRGB(0, 0, width, height, pixels, 0, width);
        return img;
    }

    private static BufferedImage createFlipped(BufferedImage image) {
        AffineTransform at = new AffineTransform();
        /*
         * Creates a compound affine transform, instead of just one, as we need
         * to perform two transformations.
         *
         * The first one is to scale the image to 100% width, and -100% height.
         * (That's *negative* 100%.)
         */
        at.concatenate(AffineTransform.getScaleInstance(1, -1));
        /*
         * We then need to translate the image back up by it's height, as flipping
         * it over moves it off the bottom of the canvas.
         */
        at.concatenate(AffineTransform.getTranslateInstance(0, -image.getHeight()));
        return createTransformed(image, at);
    }

    private static BufferedImage createTransformed(BufferedImage image, AffineTransform at) {
        // Create a blank image with the same dimensions as the old one...
        BufferedImage newImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        // ...get it's renderer...
        Graphics2D g = newImage.createGraphics();
        /// ...and draw the old image on top of it with our transform.
        g.transform(at);
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return newImage;
    }

    private String sanitize(String str) {
        return str.replaceAll("[^A-Za-z0-9-_ ]", "_");
    }
}

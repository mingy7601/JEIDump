package com.jeidump.dump;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidStack;

import mezz.jei.api.gui.IRecipeLayoutDrawable;

import com.jeidump.JeiDump;
import com.jeidump.config.JeiDumpConfig;


/**
 * Off-screen rendering of JEI's GUI primitives into PNG files.
 *
 * Strategy: bind a fresh {@link Framebuffer}, set up an orthographic projection that matches the
 * coordinate system used by Minecraft GUIs (origin top-left, Y increasing downward, no DPI/scale
 * factor applied), invoke the JEI drawing routine, then read the framebuffer back with
 * {@code glReadPixels} and encode the result as a PNG via {@link ImageIO}.
 *
 * Important pitfalls handled here:
 *  - Pixels come back bottom-up from OpenGL; we flip them while building the {@link BufferedImage}.
 *  - We must save and restore Minecraft's projection / modelview matrices and the active
 *    framebuffer binding, otherwise the next vanilla GUI frame will render at the wrong scale or
 *    into our scratch FBO.
 *  - We clear with a fully transparent color so PNGs preserve alpha; the depth buffer must also
 *    be cleared because some recipe layouts draw 3D items that rely on it.
 */
public class IconRenderer {

    /** A drawing callback executed while our scratch framebuffer is active. */
    @FunctionalInterface
    public interface DrawCommand {
        void draw();
    }

    /** Outcome of a category background deduplication attempt. */
    public static class DeduplicationResult {
        public final long originalBytes;
        public final long deduplicatedBytes;
        public final boolean applied;

        private DeduplicationResult(long originalBytes, long deduplicatedBytes, boolean applied) {
            this.originalBytes = originalBytes;
            this.deduplicatedBytes = deduplicatedBytes;
            this.applied = applied;
        }

        public long getSavedBytes() {
            return applied ? Math.max(0L, originalBytes - deduplicatedBytes) : 0L;
        }
    }

    /**
     * Renders a complete recipe layout (background + slots + extras + tooltips).
     *
     * @param layout    JEI's drawable layout, ready to draw at (0,0).
     * @param w         logical background width (px) reported by the recipe category.
     * @param h         logical background height (px) reported by the recipe category.
     * @param padding   logical pixels of empty space added on every side. JEI's recipe
     *                  categories sometimes draw labels and arrows that poke outside the
     *                  background rectangle, so we always render onto a slightly larger canvas
     *                  to avoid clipping. Slot coordinates in the JSON are pre-shifted by this
     *                  same value so frontend hotspots stay aligned.
     * @param out       destination PNG file.
     */
    public void renderRecipeLayout(IRecipeLayoutDrawable layout, int w, int h, int padding, File out) throws IOException {
        // Position the layout inside the padded canvas so labels that draw at negative coords
        // (or beyond the bg width/height) still land on the framebuffer.
        layout.setPosition(padding, padding);
        int canvasW = w + padding * 2;
        int canvasH = h + padding * 2;
        // Pass impossible mouse coordinates so JEI doesn't draw the hover highlight or tooltip.
        // Recipe scale is read from config; higher values produce crisper output PNGs.
        renderToFile(canvasW, canvasH, JeiDumpConfig.recipeScale,
            () -> layout.drawRecipe(Minecraft.getMinecraft(), -10000, -10000), out);
    }

    /** Renders a 16x16 ItemStack icon, including overlays (durability bar, count) and enchant glint. */
    public void renderItemIcon(ItemStack stack, File out) throws IOException {
        renderToFile(16, 16, 1, () -> {
            RenderHelper.enableGUIStandardItemLighting();
            RenderItem ri = Minecraft.getMinecraft().getRenderItem();
            float prevZ = ri.zLevel;
            ri.zLevel = 0;
            ri.renderItemAndEffectIntoGUI(stack, 0, 0);
            ri.renderItemOverlays(Minecraft.getMinecraft().fontRenderer, stack, 0, 0);
            ri.zLevel = prevZ;
            RenderHelper.disableStandardItemLighting();
        }, out);
    }

    /**
     * Renders a 16x16 fluid icon by drawing the still-fluid texture sampled from the texture atlas.
     * This is intentionally crude: a true JEI fluid renderer would tint and tile, but we just want
     * a recognisable thumbnail for search results.
     */
    public void renderFluidIcon(FluidStack fluid, File out) throws IOException {
        renderToFile(16, 16, 1, () -> {
            if (fluid == null || fluid.getFluid() == null) return;
            ResourceLocation tex = fluid.getFluid().getStill(fluid);
            if (tex == null) return;
            TextureAtlasSprite sprite =
                Minecraft.getMinecraft().getTextureMapBlocks().getAtlasSprite(tex.toString());
            int color = fluid.getFluid().getColor(fluid);
            float a = ((color >> 24) & 0xFF) / 255f; if (a == 0f) a = 1f;
            float r = ((color >> 16) & 0xFF) / 255f;
            float g = ((color >> 8) & 0xFF) / 255f;
            float b = (color & 0xFF) / 255f;
            GlStateManager.color(r, g, b, a);
            Minecraft.getMinecraft().getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
            Tessellator t = Tessellator.getInstance();
            BufferBuilder bb = t.getBuffer();
            bb.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
            bb.pos(0, 16, 0).tex(sprite.getMinU(), sprite.getMaxV()).endVertex();
            bb.pos(16, 16, 0).tex(sprite.getMaxU(), sprite.getMaxV()).endVertex();
            bb.pos(16, 0, 0).tex(sprite.getMaxU(), sprite.getMinV()).endVertex();
            bb.pos(0, 0, 0).tex(sprite.getMinU(), sprite.getMinV()).endVertex();
            t.draw();
            GlStateManager.color(1, 1, 1, 1);
        }, out);
    }

    /**
     * Extract the pixel-identical background shared by every recipe in a category, keep it once,
     * and rewrite each recipe PNG in place as a transparent foreground layer.
     * <p>
     * The split is only kept if the encoded PNG bytes shrink compared to the original recipe set.
     */
    public DeduplicationResult deduplicateCategoryBackground(List<File> recipeFiles, File backgroundFile) throws IOException {
        long originalBytes = totalBytes(recipeFiles);
        if (recipeFiles.size() < 2) return new DeduplicationResult(originalBytes, originalBytes, false);

        BufferedImage first = readPng(recipeFiles.get(0));
        if (first == null) return new DeduplicationResult(originalBytes, originalBytes, false);

        int width = first.getWidth();
        int height = first.getHeight();
        int[] sharedPixels = first.getRGB(0, 0, width, height, null, 0, width);
        boolean[] sharedMask = new boolean[sharedPixels.length];
        Arrays.fill(sharedMask, true);

        for (int fileIndex = 1; fileIndex < recipeFiles.size(); fileIndex++) {
            File recipeFile = recipeFiles.get(fileIndex);
            BufferedImage image = readPng(recipeFile);
            if (image == null || image.getWidth() != width || image.getHeight() != height) {
                return new DeduplicationResult(originalBytes, originalBytes, false);
            }

            int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
            for (int pixelIndex = 0; pixelIndex < sharedPixels.length; pixelIndex++) {
                if (sharedMask[pixelIndex] && pixels[pixelIndex] != sharedPixels[pixelIndex]) {
                    sharedMask[pixelIndex] = false;
                }
            }
        }

        int[] backgroundPixels = new int[sharedPixels.length];
        int sharedOpaquePixels = 0;
        for (int pixelIndex = 0; pixelIndex < sharedPixels.length; pixelIndex++) {
            if (!sharedMask[pixelIndex]) continue;

            int pixel = sharedPixels[pixelIndex];
            backgroundPixels[pixelIndex] = pixel;
            if ((pixel >>> 24) != 0) sharedOpaquePixels++;
        }

        if (sharedOpaquePixels == 0) return new DeduplicationResult(originalBytes, originalBytes, false);

        BufferedImage background = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        background.setRGB(0, 0, width, height, backgroundPixels, 0, width);

        byte[] backgroundBytes = pngBytes(background);
        List<byte[]> foregroundBytes = new ArrayList<>(recipeFiles.size());
        long splitBytes = backgroundBytes.length;

        for (File recipeFile : recipeFiles) {
            BufferedImage image = readPng(recipeFile);
            if (image == null || image.getWidth() != width || image.getHeight() != height) {
                return new DeduplicationResult(originalBytes, originalBytes, false);
            }

            int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
            for (int pixelIndex = 0; pixelIndex < pixels.length; pixelIndex++) {
                if (sharedMask[pixelIndex]) pixels[pixelIndex] = 0;
            }

            BufferedImage foreground = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            foreground.setRGB(0, 0, width, height, pixels, 0, width);

            byte[] encoded = pngBytes(foreground);
            splitBytes += encoded.length;
            foregroundBytes.add(encoded);
        }

        if (splitBytes >= originalBytes) return new DeduplicationResult(originalBytes, originalBytes, false);

        Files.write(backgroundFile.toPath(), backgroundBytes);
        for (int fileIndex = 0; fileIndex < recipeFiles.size(); fileIndex++) {
            Files.write(recipeFiles.get(fileIndex).toPath(), foregroundBytes.get(fileIndex));
        }

        return new DeduplicationResult(originalBytes, splitBytes, true);
    }

    private static long totalBytes(List<File> files) throws IOException {
        long total = 0L;
        for (File file : files) total += Files.size(file.toPath());

        return total;
    }

    private static BufferedImage readPng(File file) throws IOException {
        return ImageIO.read(file);
    }

    private static byte[] pngBytes(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "PNG", out)) {
            throw new IOException("No PNG writer available");
        }

        return out.toByteArray();
    }

    /**
     * Common framebuffer setup / draw / readback / teardown.
     *
     * @param w     logical canvas width (GUI coords).
     * @param h     logical canvas height (GUI coords).
     * @param scale integer pixel multiplier. The framebuffer is allocated at (w*scale)x(h*scale)
     *              while the orthographic projection stays in logical coords, so callers don't
     *              need to know about the scale: every draw call ends up super-sampled.
     */
    private void renderToFile(int w, int h, int scale, DrawCommand draw, File out) throws IOException {
        Minecraft mc = Minecraft.getMinecraft();
        int fbW = w * scale;
        int fbH = h * scale;
        Framebuffer fb = new Framebuffer(fbW, fbH, true);
        fb.setFramebufferColor(0f, 0f, 0f, 0f);
        fb.framebufferClear();
        fb.bindFramebuffer(true);

        // Save matrices.
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        // Match Minecraft GUI coords: (0,0) top-left, +Y down. ortho(left,right,bottom,top,near,far).
        // We deliberately keep the ortho range in *logical* units; the viewport stretches to the
        // scaled framebuffer, which is what makes super-sampling transparent to JEI's draw code.
        GlStateManager.ortho(0, w, h, 0, 1000, 3000);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.translate(0, 0, -2000);

        GlStateManager.viewport(0, 0, fbW, fbH);
        GlStateManager.clearColor(0f, 0f, 0f, 0f);
        GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.color(1f, 1f, 1f, 1f);

        try {
            draw.draw();
        } catch (Throwable t) {
            // Don't let one bad recipe wreck the whole dump; log and write whatever the FB contains.
            JeiDump.LOGGER.warn("Render failure for {}: {}", out.getName(), t.toString());
        }

        // Read back pixels as RGBA bytes.
        ByteBuffer buf = BufferUtils.createByteBuffer(fbW * fbH * 4);
        GL11.glReadPixels(0, 0, fbW, fbH, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);

        // Restore matrices and active framebuffer.
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.popMatrix();
        fb.unbindFramebuffer();
        fb.deleteFramebuffer();
        Framebuffer mcFb = mc.getFramebuffer();
        if (mcFb != null) {
            mcFb.bindFramebuffer(true);
            GlStateManager.viewport(0, 0, mcFb.framebufferWidth, mcFb.framebufferHeight);
        }

        // Convert (origin bottom-left, RGBA bytes) to BufferedImage (origin top-left, ARGB int).
        BufferedImage img = new BufferedImage(fbW, fbH, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < fbH; y++) {
            for (int x = 0; x < fbW; x++) {
                int i = ((fbH - 1 - y) * fbW + x) * 4;
                int r = buf.get(i) & 0xFF;
                int g = buf.get(i + 1) & 0xFF;
                int b = buf.get(i + 2) & 0xFF;
                int a = buf.get(i + 3) & 0xFF;
                img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }

        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();
        ImageIO.write(img, "PNG", out);
    }
}

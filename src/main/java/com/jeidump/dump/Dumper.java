package com.jeidump.dump;

import java.awt.Rectangle;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import net.minecraft.client.Minecraft;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fluids.FluidStack;

import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.IRecipeRegistry;
import mezz.jei.api.gui.IGuiFluidStackGroup;
import mezz.jei.api.gui.IGuiIngredient;
import mezz.jei.api.gui.IGuiItemStackGroup;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeWrapper;

import com.jeidump.JeiDump;
import com.jeidump.command.CommandDumpJei;
import com.jeidump.config.JeiDumpConfig;
import com.jeidump.i18n.JeiDumpLocales;


/**
 * Stateful JEI dumper.
 *
 * The work is split into three phases:
 * <ol>
 *   <li>{@link #setup()}: prepares the output directory tree and pre-counts recipes.</li>
 *   <li>{@link #step(int)}: processes up to {@code budget} recipes per call. Returns
 *       {@code true} while there is more work to do; the caller (a Forge {@code ClientTickEvent}
 *       handler) invokes this once per tick to keep the OS event loop alive.</li>
 *   <li>{@link #finish()}: writes locale-aware data files and copies the bundled web frontend.</li>
 * </ol>
 *
 * Output structure (relative to {@code outDir}):
 * <pre>
 *   index.html, assets/style.css, assets/app.js
 *   assets/lang/<locale>.lang, assets/lang/index.json, assets/lang/index.js
 *   data/index.json
 *   data/manifest.json, data/manifest.js
 *   data/locales/<locale>/index.json
 *   data/locales/<locale>/categories/<cat>/background.png (when deduplication wins)
 *   data/locales/<locale>/categories/<cat>/recipe_N.png
 *   data/locales/<locale>/items/<id>.png
 *   data/locales/<locale>/fluids/<id>.png
 * </pre>
 *
 * Per-recipe JSON now also includes:
 * <ul>
 *   <li>{@code img}: the full recipe PNG, or the per-recipe foreground layer when the category
 *       also exposes {@code backgroundImg}.</li>
 *   <li>{@code slots}: array of {@code {x,y,w,h,id,kind,role}} so the frontend can overlay
 *       hotspots that exactly match JEI's layout for hover/tooltip + click navigation.</li>
 * </ul>
 *
 * Per-category JSON may also include:
 * <ul>
 *   <li>{@code backgroundImg}: category-wide shared background layer, emitted only when splitting
 *       the recipe PNGs actually reduces their total encoded size.</li>
 * </ul>
 *
 * Per-ingredient meta now also includes:
 * <ul>
 *   <li>{@code tooltip}: array of plain strings (vanilla NORMAL flag, color codes stripped),
 *       used by the frontend to render JEI-like hover tooltips.</li>
 * </ul>
 *
 * Root metadata also includes:
 * <ul>
 *   <li>{@code generatedAt}: ISO-8601 timestamp captured once when the dump starts, used by the
 *       website footer.</li>
 * </ul>
 */
public class Dumper {

    /** Summary returned to the chat once the dump finishes. */
    public static class Result {
        public int categoryCount;
        public int recipeCount;
        public int iconCount;
    }

    private static final String[] RESOURCE_FILES = {
        "assets/jeidump/web/index.html:index.html",
        "assets/jeidump/web/style.css:assets/style.css",
        "assets/jeidump/web/app.js:assets/app.js"
    };

    /**
     * Logical pixels of empty space added on every side of every recipe layout PNG. Some JEI
     * categories (notably modded ones with long titles or arrows that extend past the
     * background) draw outside the box reported by {@code IRecipeCategory#getBackground()};
     * without padding those labels get clipped at the image edge. The frontend hotspots are
     * shifted by the same value so click targets stay aligned.
     */
    private static final int RECIPE_PADDING = 8;

    private final IJeiRuntime runtime;
    private final File outDir;
    private final ICommandSender sender;
    private final IconRenderer renderer = new IconRenderer();
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final String dumpLocale = JeiDumpLocales.getCurrentLocaleCode();
    private final String generatedAt = Instant.now().toString();

    /** Item icon dedup: stable key -> sanitised filename stem. */
    private final Map<String, String> itemIds = new LinkedHashMap<>();
    /** Item icon metadata (display name, mod, tooltip). Key matches {@link #itemIds}. */
    private final Map<String, JsonObject> itemMeta = new LinkedHashMap<>();
    /** Fluid icon dedup. */
    private final Map<String, String> fluidIds = new LinkedHashMap<>();
    private final Map<String, JsonObject> fluidMeta = new LinkedHashMap<>();
    /** Inverted index: itemId -> list of {cat, idx, role} pointing at recipes that contain it. */
    private final Map<String, JsonArray> itemRecipes = new LinkedHashMap<>();

    // Cached reflective handle to mezz.jei.gui.ingredients.GuiIngredient#getRect().
    // The interface IGuiIngredient does not expose slot positions, but JEI's only concrete
    // implementation does; reading it is the cleanest way to mirror JEI's hover hotspots
    // without re-deriving them from the recipe category.
    @Nullable
    private static Method getRectMethod;
    private static boolean getRectResolved;

    // Cached reflective handle to mezz.jei.gui.Focus(IFocus.Mode, V). Used as a fallback when
    // a wrapper's setRecipe NPEs on a null focus (some mods register custom wrappers under
    // vanilla categories that violate the @Nullable contract). JEI 4.x has no public focus
    // factory; the internal Focus class is the only way.
    @Nullable
    private static Constructor<?> focusCtor;
    private static boolean focusCtorResolved;
    @Nullable
    private static IFocus<ItemStack> fallbackFocus;

    // Phase state
    private File dataDir, localesRoot, localeDataDir, catRoot, itemRoot, fluidRoot;
    @SuppressWarnings("rawtypes")
    private List<IRecipeCategory> categories;
    private int totalRecipes;
    private int catIdx;          // current category index
    private int wrapperIdx;      // current wrapper inside the active category
    private List<IRecipeWrapper> currentWrappers;
    private IRecipeCategory<?> currentCategory;
    private String currentCatId;
    private File currentCatFolder;
    private int currentBgW, currentBgH;
    private JsonObject currentCatObj;
    private JsonArray currentRecipesJson;
    private final JsonArray categoriesJson = new JsonArray();

    private final Result result = new Result();

    public Dumper(IJeiRuntime runtime, File outDir, ICommandSender sender) {
        this.runtime = runtime;
        this.outDir = outDir;
        this.sender = sender;
    }

    public Result getResult() {
        return result;
    }

    /** Create directories, snapshot categories, pre-count recipes for progress reporting. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void setup() throws IOException {
        dataDir = new File(outDir, "data");
        localesRoot = new File(dataDir, "locales");
        localeDataDir = new File(localesRoot, dumpLocale);
        catRoot = new File(localeDataDir, "categories");
        itemRoot = new File(localeDataDir, "items");
        fluidRoot = new File(localeDataDir, "fluids");
        if (!dataDir.mkdirs() && !dataDir.exists()) throw new IOException("Cannot create " + dataDir);
        if (!localesRoot.mkdirs() && !localesRoot.exists()) throw new IOException("Cannot create " + localesRoot);
        if (localeDataDir.exists()) {
            deleteTree(localeDataDir.toPath());
        }
        if (!localeDataDir.mkdirs() && !localeDataDir.exists()) throw new IOException("Cannot create " + localeDataDir);
        catRoot.mkdirs();
        itemRoot.mkdirs();
        fluidRoot.mkdirs();
        new File(outDir, "assets").mkdirs();

        IRecipeRegistry rr = runtime.getRecipeRegistry();
        categories = rr.getRecipeCategories();
        result.categoryCount = categories.size();

        for (IRecipeCategory cat : categories) {
            totalRecipes += rr.getRecipeWrappers(cat).size();
        }
        CommandDumpJei.info(sender, "jeidump.command.scan_total", totalRecipes, categories.size());

        catIdx = 0;
        wrapperIdx = 0;
        primeCurrentCategory();
    }

    /**
     * Process up to {@code budget} recipes. Returns {@code true} if there is more work to do.
     * The caller is expected to invoke this once per client tick so the OS event loop keeps
     * pumping (avoids "Not Responding" / DWM kill).
     */
    public boolean step(int budget) {
        if (categories == null) return false;
        IRecipeRegistry rr = runtime.getRecipeRegistry();
        int processed = 0;

        while (processed < budget) {
            // Skip empty categories or advance past the end of the current one.
            while (currentCategory != null && wrapperIdx >= currentWrappers.size()) {
                finalizeCurrentCategory();
                catIdx++;
                wrapperIdx = 0;
                if (catIdx >= categories.size()) {
                    return false;
                }
                primeCurrentCategory();
            }
            if (currentCategory == null) return false;

            IRecipeWrapper wrapper = currentWrappers.get(wrapperIdx);
            try {
                IRecipeLayoutDrawable layout = createLayoutWithRetry(rr, currentCategory, wrapper);
                if (layout != null) {
                    File pngFile = new File(currentCatFolder, "recipe_" + wrapperIdx + ".png");
                    renderer.renderRecipeLayout(layout, currentBgW, currentBgH, RECIPE_PADDING, pngFile);

                    // Logical canvas size including the padding band. The PNG file itself is this
                    // size multiplied by IconRenderer.RECIPE_SCALE, but the frontend works in
                    // logical units (positions hotspots in % of the logical canvas).
                    int canvasW = currentBgW + RECIPE_PADDING * 2;
                    int canvasH = currentBgH + RECIPE_PADDING * 2;

                    JsonObject recObj = new JsonObject();
                    // During the final deduplication pass this file may be rewritten in place as
                    // a foreground-only layer if the shared background split is smaller on disk.
                    recObj.addProperty("img", localeDataPath("categories/" + currentCatId + "/recipe_" + wrapperIdx + ".png"));
                    recObj.addProperty("w", canvasW);
                    recObj.addProperty("h", canvasH);
                    // Pixel multiplier baked into the PNG. The frontend uses this to display the
                    // image at integer multiples of the logical size (1x, 2x, ...) instead of
                    // stretching it to fit the card width.
                    recObj.addProperty("scale", JeiDumpConfig.recipeScale);

                    JsonArray inputs = new JsonArray();
                    JsonArray outputs = new JsonArray();
                    JsonArray fluidInputs = new JsonArray();
                    JsonArray fluidOutputs = new JsonArray();
                    JsonArray slots = new JsonArray();

                    collectItemSlots(layout.getItemStacks(), currentCatId, wrapperIdx, inputs, outputs, slots);
                    collectFluidSlots(layout.getFluidStacks(), currentCatId, wrapperIdx, fluidInputs, fluidOutputs, slots);

                    recObj.add("inputs", inputs);
                    recObj.add("outputs", outputs);
                    recObj.add("fluidInputs", fluidInputs);
                    recObj.add("fluidOutputs", fluidOutputs);
                    recObj.add("slots", slots);
                    currentRecipesJson.add(recObj);
                }
            } catch (Throwable t) {
                JeiDump.LOGGER.warn("Skipping recipe #{} of category {}: {}", wrapperIdx, currentCategory.getUid(), t.toString());
            }

            wrapperIdx++;
            result.recipeCount++;
            processed++;

            // Roughly every 200 recipes, post a progress line. Tied to global count, not per-tick
            // budget, so the message density is independent of the tick budget knob.
            if (result.recipeCount % 200 == 0) {
                CommandDumpJei.progress(sender, result.recipeCount, totalRecipes, currentCategory.getTitle());
            }
        }

        return true;
    }

    /** Write locale-aware dump data + copy bundled web assets. Call after {@link #step(int)} returns false. */
    public Result finish() throws IOException {
        // Make sure the trailing category gets flushed if step() returned with the loop exhausted.
        if (currentCategory != null && wrapperIdx >= currentWrappers.size()) {
            finalizeCurrentCategory();
        }

        CommandDumpJei.info(sender, "jeidump.command.dedup.start");
        long dedupSavedBytes = deduplicateRecipeBackgrounds();

        JsonObject root = buildDataRoot();
        writeJson(root, new File(localeDataDir, "index.json"));
        writeLocaleDataScript(root, new File(localeDataDir, "index.js"), dumpLocale);

        // Keep a root-level alias for the most recent dump so external tools and pre-locale sites
        // can still open the folder without knowing about the locale manifest.
        writeJson(root, new File(dataDir, "index.json"));
        writeLegacyDataScript(root, new File(dataDir, "index.js"), dumpLocale);

        writeDataManifest();
        writeLangBundle();

        for (String pair : RESOURCE_FILES) {
            int colon = pair.indexOf(':');
            String src = pair.substring(0, colon);
            File dst = new File(outDir, pair.substring(colon + 1));
            copyResource(src, dst);
        }

        result.iconCount = itemIds.size() + fluidIds.size();

        long finalDumpBytes = measureTreeBytes(outDir.toPath());
        if (dedupSavedBytes > 0L) {
            long originalDumpBytes = finalDumpBytes + dedupSavedBytes;
            String previous = formatMiB(originalDumpBytes);
            String current = formatMiB(finalDumpBytes);
            String percent = String.format(Locale.ROOT, "%.1f", finalDumpBytes * 100.0 / originalDumpBytes);
            CommandDumpJei.info(sender, "jeidump.command.dedup.done", previous, current, percent);
        } else {
            CommandDumpJei.info(sender, "jeidump.command.dedup.none");
        }

        return result;
    }

    // ----- per-category bookkeeping -----

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void primeCurrentCategory() {
        if (catIdx >= categories.size()) {
            currentCategory = null;
            return;
        }
        IRecipeCategory<?> cat = categories.get(catIdx);
        currentCategory = cat;
        currentCatId = sanitize(cat.getUid());
        currentCatFolder = new File(catRoot, currentCatId);
        currentCatFolder.mkdirs();
        currentBgW = safePositive(cat.getBackground().getWidth(), 128);
        currentBgH = safePositive(cat.getBackground().getHeight(), 64);

        currentCatObj = new JsonObject();
        currentCatObj.addProperty("id", currentCatId);
        currentCatObj.addProperty("uid", cat.getUid());
        currentCatObj.addProperty("title", cat.getTitle());
        currentCatObj.addProperty("modName", cat.getModName());
        currentRecipesJson = new JsonArray();

        currentWrappers = (List) runtime.getRecipeRegistry().getRecipeWrappers((IRecipeCategory) cat);
    }

    private void finalizeCurrentCategory() {
        if (currentCategory == null) return;

        currentCatObj.addProperty("recipeCount", currentRecipesJson.size());
        currentCatObj.add("recipes", currentRecipesJson);
        categoriesJson.add(currentCatObj);
        currentCategory = null;
    }

    // ----- ingredient collection -----

    private void collectItemSlots(IGuiItemStackGroup group, String catId, int recipeIdx,
                                  JsonArray inputs, JsonArray outputs, JsonArray slots) throws IOException {
        for (IGuiIngredient<ItemStack> ig : group.getGuiIngredients().values()) {
            ItemStack disp = ig.getDisplayedIngredient();
            if (disp == null || disp.isEmpty()) {
                disp = firstNonEmpty(ig.getAllIngredients());
            }
            if (disp == null || disp.isEmpty()) continue;

            String id = idForItem(disp);
            if (!itemIds.containsKey(id)) {
                itemIds.put(id, id);
                File png = new File(itemRoot, id + ".png");
                try {
                    renderer.renderItemIcon(disp, png);
                } catch (Throwable t) {
                    JeiDump.LOGGER.warn("Failed to render item icon for {}: {}", id, t.toString());
                }
                JsonObject meta = new JsonObject();
                meta.addProperty("name", safeName(disp));
                meta.addProperty("mod", modIdOf(disp));
                meta.addProperty("img", localeDataPath("items/" + id + ".png"));
                meta.add("tooltip", buildItemTooltip(disp));
                itemMeta.put(id, meta);
            }
            (ig.isInput() ? inputs : outputs).add(new JsonPrimitive(id));
            addInverted(id, catId, recipeIdx, ig.isInput() ? "in" : "out", "item");
            addSlot(slots, ig, id, "item", RECIPE_PADDING);
        }
    }

    private void collectFluidSlots(IGuiFluidStackGroup group, String catId, int recipeIdx,
                                   JsonArray inputs, JsonArray outputs, JsonArray slots) throws IOException {
        for (IGuiIngredient<FluidStack> ig : group.getGuiIngredients().values()) {
            FluidStack disp = ig.getDisplayedIngredient();
            if (disp == null) {
                List<FluidStack> all = ig.getAllIngredients();
                if (all != null) {
                    for (FluidStack f : all) { if (f != null) { disp = f; break; } }
                }
            }
            if (disp == null || disp.getFluid() == null) continue;

            String id = "fluid_" + sanitize(disp.getFluid().getName());
            if (!fluidIds.containsKey(id)) {
                fluidIds.put(id, id);
                File png = new File(fluidRoot, id + ".png");
                try {
                    renderer.renderFluidIcon(disp, png);
                } catch (Throwable t) {
                    JeiDump.LOGGER.warn("Failed to render fluid icon for {}: {}", id, t.toString());
                }
                JsonObject meta = new JsonObject();
                meta.addProperty("name", disp.getLocalizedName());
                meta.addProperty("mod", modIdOf(disp));
                meta.addProperty("img", localeDataPath("fluids/" + id + ".png"));
                meta.add("tooltip", buildFluidTooltip(disp));
                fluidMeta.put(id, meta);
            }
            (ig.isInput() ? inputs : outputs).add(new JsonPrimitive(id));
            addInverted(id, catId, recipeIdx, ig.isInput() ? "in" : "out", "fluid");
            addSlot(slots, ig, id, "fluid", RECIPE_PADDING);
        }
    }

    /**
     * Build a recipe layout, retrying with a non-null placeholder focus if the first attempt
     * NPEs. JEI's API marks the focus argument {@code @Nullable}, but a handful of mod-supplied
     * recipe wrappers (notably some that piggyback on the vanilla crafting category) call
     * {@code Preconditions.checkNotNull(focus)} inside their {@code setRecipe} and crash. The
     * placeholder focus is a dummy OUTPUT focus on a stick; its only purpose is to be non-null,
     * the wrapper's actual ingredients still come from {@code wrapper.getIngredients}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Nullable
    private static IRecipeLayoutDrawable createLayoutWithRetry(IRecipeRegistry rr, IRecipeCategory<?> cat, IRecipeWrapper wrapper) {
        try {
            return rr.createRecipeLayoutDrawable((IRecipeCategory) cat, wrapper, null);
        } catch (NullPointerException npe) {
            IFocus<ItemStack> focus = getFallbackFocus();
            if (focus == null) throw npe; // can't recover, let the outer catch log it
            return rr.createRecipeLayoutDrawable((IRecipeCategory) cat, wrapper, focus);
        }
    }

    /**
     * Reflectively construct (and cache) a non-null {@link IFocus}. JEI 4.x doesn't expose any
     * public way to build one, but the internal {@code mezz.jei.gui.Focus} ctor is stable across
     * the 4.x line. Returns {@code null} if the class layout differs (e.g. a fork) so the caller
     * can fall back to skipping the recipe.
     */
    @SuppressWarnings("unchecked")
    @Nullable
    private static IFocus<ItemStack> getFallbackFocus() {
        if (fallbackFocus != null) return fallbackFocus;
        if (!focusCtorResolved) {
            focusCtorResolved = true;
            try {
                Class<?> focusCls = Class.forName("mezz.jei.gui.Focus");
                focusCtor = focusCls.getConstructor(IFocus.Mode.class, Object.class);
            } catch (Throwable t) {
                JeiDump.LOGGER.warn("Cannot resolve mezz.jei.gui.Focus, focus-NPE recipes will be skipped: {}", t.toString());
            }
        }
        if (focusCtor == null) return null;
        try {
            // A stick is always present, never empty, and registered as an item ingredient type.
            ItemStack placeholder = new ItemStack(Items.STICK);
            fallbackFocus = (IFocus<ItemStack>) focusCtor.newInstance(IFocus.Mode.OUTPUT, placeholder);
        } catch (Throwable t) {
            JeiDump.LOGGER.warn("Cannot construct fallback focus: {}", t.toString());
        }
        return fallbackFocus;
    }

    /**
     * Append a slot rect to the per-recipe slots array, if we can read the rect via reflection.
     * The rect is shifted by {@code padding} on both axes because the recipe layout is drawn at
     * {@code (padding, padding)} on the padded canvas; without the offset the frontend hotspots
     * would land in the empty band on the top-left of the image.
     */
    private static void addSlot(JsonArray slots, IGuiIngredient<?> ig, String id, String kind, int padding) {
        Rectangle r = readRect(ig);
        if (r == null) return;
        JsonObject slot = new JsonObject();
        slot.addProperty("x", r.x + padding);
        slot.addProperty("y", r.y + padding);
        slot.addProperty("w", r.width);
        slot.addProperty("h", r.height);
        slot.addProperty("id", id);
        slot.addProperty("kind", kind);
        slot.addProperty("role", ig.isInput() ? "in" : "out");
        slots.add(slot);
    }

    /**
     * Reflectively read {@code GuiIngredient#getRect()}. Cached on first call.
     * Returns {@code null} if the implementation doesn't expose it (e.g. a custom IGuiIngredient
     * supplied by another mod).
     */
    @Nullable
    private static Rectangle readRect(IGuiIngredient<?> ig) {
        if (!getRectResolved) {
            getRectResolved = true;
            try {
                getRectMethod = Class.forName("mezz.jei.gui.ingredients.GuiIngredient").getMethod("getRect");
            } catch (Throwable t) {
                JeiDump.LOGGER.warn("Cannot resolve GuiIngredient#getRect, slot hotspots will be missing: {}", t.toString());
            }
        }
        if (getRectMethod == null) return null;
        try {
            // The method is declared on the concrete class; accept any IGuiIngredient that is
            // an instance of that class. Other implementations silently get no rect.
            if (!getRectMethod.getDeclaringClass().isInstance(ig)) return null;
            Object o = getRectMethod.invoke(ig);
            return (o instanceof Rectangle) ? (Rectangle) o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Capture the vanilla NORMAL-flag tooltip; strip color codes for plain HTML rendering. */
    private static JsonArray buildItemTooltip(ItemStack stack) {
        JsonArray arr = new JsonArray();
        try {
            EntityPlayer player = Minecraft.getMinecraft().player;
            // Some vanilla / modded tooltip code crashes when player is null at the main menu.
            // The command runs in-world so player should be non-null, but guard anyway.
            List<String> lines = stack.getTooltip(player, ITooltipFlag.TooltipFlags.NORMAL);
            for (String line : lines) {
                arr.add(new JsonPrimitive(TextFormatting.getTextWithoutFormattingCodes(line)));
            }
        } catch (Throwable t) {
            // Fall back to display name only.
            arr.add(new JsonPrimitive(safeName(stack)));
        }
        return arr;
    }

    private static JsonArray buildFluidTooltip(FluidStack stack) {
        JsonArray arr = new JsonArray();
        arr.add(new JsonPrimitive(stack.getLocalizedName()));
        arr.add(new JsonPrimitive(stack.amount + " mB"));
        return arr;
    }

    private void addInverted(String id, String catId, int recipeIdx, String role, String kind) {
        JsonArray arr = itemRecipes.get(id);
        if (arr == null) {
            arr = new JsonArray();
            itemRecipes.put(id, arr);
        }
        JsonObject ref = new JsonObject();
        ref.addProperty("cat", catId);
        ref.addProperty("idx", recipeIdx);
        ref.addProperty("role", role);
        ref.addProperty("kind", kind);
        arr.add(ref);
    }

    /**
     * Build a stable, filesystem-safe identifier for an item: domain_path_meta_nbthash.
     * NBT presence is hashed instead of serialised so we don't blow up the filename for items with
     * complex tags (enchanted books, written books, etc.).
     */
    private static String idForItem(ItemStack stack) {
        ResourceLocation rn = stack.getItem().getRegistryName();
        String base = rn == null ? "unknown_unknown" : (rn.getNamespace() + "_" + rn.getPath());
        int meta = stack.getMetadata();
        String nbtPart = "";
        if (stack.hasTagCompound() && stack.getTagCompound() != null) {
            nbtPart = "_" + Integer.toHexString(stack.getTagCompound().hashCode());
        }
        return sanitize(base + "_" + meta + nbtPart);
    }

    private static String modIdOf(ItemStack stack) {
        ResourceLocation rn = stack.getItem().getRegistryName();
        return rn == null ? "minecraft" : rn.getNamespace();
    }

    private static String modIdOf(FluidStack fluid) {
        // FluidStack doesn't carry a registry name; best effort via the still texture's domain.
        if (fluid.getFluid() == null) return "minecraft";
        ResourceLocation tex = fluid.getFluid().getStill();
        return tex == null ? "minecraft" : tex.getNamespace();
    }

    private static String safeName(ItemStack stack) {
        try {
            return stack.getDisplayName();
        } catch (Throwable t) {
            // Some buggy items throw during getDisplayName when their NBT is malformed.
            ResourceLocation rn = stack.getItem().getRegistryName();
            return rn == null ? "unknown" : rn.toString();
        }
    }

    @Nullable
    private static ItemStack firstNonEmpty(@Nullable List<ItemStack> list) {
        if (list == null) return null;
        for (ItemStack s : list) {
            if (s != null && !s.isEmpty()) return s;
        }
        return null;
    }

    private static int safePositive(int v, int fallback) {
        return v > 0 ? v : fallback;
    }

    private static String sanitize(String s) {
        // Disallow path traversal and any character that's awkward across Windows + Unix filesystems.
        return s.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private JsonObject buildDataRoot() {
        JsonObject root = new JsonObject();
        root.addProperty("locale", dumpLocale);
        root.addProperty("generatedAt", generatedAt);
        root.add("categories", categoriesJson);

        JsonObject itemsRoot = new JsonObject();
        for (Map.Entry<String, JsonObject> e : itemMeta.entrySet()) {
            itemsRoot.add(e.getKey(), e.getValue());
        }
        root.add("items", itemsRoot);

        JsonObject fluidsRoot = new JsonObject();
        for (Map.Entry<String, JsonObject> e : fluidMeta.entrySet()) {
            fluidsRoot.add(e.getKey(), e.getValue());
        }
        root.add("fluids", fluidsRoot);

        JsonObject itemRecipesRoot = new JsonObject();
        for (Map.Entry<String, JsonArray> e : itemRecipes.entrySet()) {
            itemRecipesRoot.add(e.getKey(), e.getValue());
        }
        root.add("itemRecipes", itemRecipesRoot);
        return root;
    }

    /**
     * Run background extraction after every recipe PNG exists, so the chat status can
     * report how much disk space the deduplication saves.
     */
    private long deduplicateRecipeBackgrounds() {
        long savedBytes = 0L;

        for (JsonElement categoryElement : categoriesJson) {
            JsonObject category = categoryElement.getAsJsonObject();
            JsonArray recipes = category.getAsJsonArray("recipes");
            if (recipes == null || recipes.size() == 0) continue;

            List<File> recipeFiles = new ArrayList<>();
            for (JsonElement recipeElement : recipes) {
                JsonObject recipe = recipeElement.getAsJsonObject();
                if (!recipe.has("img")) continue;

                recipeFiles.add(new File(outDir, recipe.get("img").getAsString()));
            }
            if (recipeFiles.isEmpty()) continue;

            String categoryId = category.get("id").getAsString();
            File backgroundFile = new File(new File(catRoot, categoryId), "background.png");

            try {
                IconRenderer.DeduplicationResult result = renderer.deduplicateCategoryBackground(recipeFiles, backgroundFile);
                if (!result.applied) continue;

                category.addProperty("backgroundImg", localeDataPath("categories/" + categoryId + "/background.png"));
                savedBytes += result.getSavedBytes();
            } catch (IOException e) {
                JeiDump.LOGGER.warn("Failed to deduplicate recipe backgrounds for category {}: {}", categoryId, e.toString());
            }
        }

        return savedBytes;
    }

    /** Dump manifest used by the website to decide which locale-specific data bundle to load. */
    private void writeDataManifest() throws IOException {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("latestDumpLocale", dumpLocale);

        JsonArray availableDataLocales = new JsonArray();
        for (String locale : listAvailableDataLocales()) availableDataLocales.add(locale);
        manifest.add("availableDataLocales", availableDataLocales);

        writeJson(manifest, new File(dataDir, "manifest.json"));
        writeGlobalScript(manifest, new File(dataDir, "manifest.js"), "window.__JEI_DUMP_MANIFEST = ");
    }

    /** Copy the bundled lang files and emit a JS-friendly translation table for the website. */
    private void writeLangBundle() throws IOException {
        File langDir = new File(new File(outDir, "assets"), "lang");
        if (!langDir.mkdirs() && !langDir.exists()) throw new IOException("Cannot create " + langDir);

        JsonObject payload = new JsonObject();
        JsonArray availableLocales = new JsonArray();
        JsonObject translations = new JsonObject();

        for (Map.Entry<String, Properties> entry : JeiDumpLocales.loadBundledLangTables().entrySet()) {
            String locale = entry.getKey();
            availableLocales.add(locale);
            copyResource(JeiDumpLocales.resourcePath(locale), new File(langDir, locale + ".lang"));

            JsonObject table = new JsonObject();
            for (String key : entry.getValue().stringPropertyNames()) {
                table.addProperty(key, entry.getValue().getProperty(key));
            }

            translations.add(locale, table);
        }

        payload.add("availableLocales", availableLocales);
        payload.add("translations", translations);

        writeJson(payload, new File(langDir, "index.json"));
        writeGlobalScript(payload, new File(langDir, "index.js"), "window.__JEI_DUMP_I18N = ");
    }

    private List<String> listAvailableDataLocales() {
        List<String> locales = new ArrayList<>();
        File[] children = localesRoot.listFiles(File::isDirectory);
        if (children == null) {
            locales.add(dumpLocale);
            return locales;
        }

        for (File child : children) {
            if (!new File(child, "index.json").isFile()) continue;
            locales.add(child.getName());
        }

        if (!locales.contains(dumpLocale)) locales.add(dumpLocale);

        JeiDumpLocales.sortLocaleCodes(locales);
        return locales;
    }

    private String localeDataPath(String relativePath) {
        return "data/locales/" + dumpLocale + "/" + relativePath;
    }

    private void writeJson(JsonObject root, File dst) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(dst.toPath(), StandardCharsets.UTF_8)) {
            gson.toJson(root, bw);
        }
    }

    private void writeLegacyDataScript(JsonObject root, File dst, String locale) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(dst.toPath(), StandardCharsets.UTF_8)) {
            bw.write("window.__JEI_DUMP_DATA = ");
            gson.toJson(root, bw);
            bw.write(";\nwindow.__JEI_DUMP_DATA_LOCALE = ");
            gson.toJson(locale, bw);
            bw.write(";\n");
        }
    }

    private void writeLocaleDataScript(JsonObject root, File dst, String locale) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(dst.toPath(), StandardCharsets.UTF_8)) {
            bw.write("window.__JEI_DUMP_DATASETS = window.__JEI_DUMP_DATASETS || {};\nwindow.__JEI_DUMP_DATASETS[");
            gson.toJson(locale, bw);
            bw.write("] = ");
            gson.toJson(root, bw);
            bw.write(";\n");
        }
    }

    private void writeGlobalScript(JsonObject root, File dst, String prefix) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(dst.toPath(), StandardCharsets.UTF_8)) {
            bw.write(prefix);
            gson.toJson(root, bw);
            bw.write(";\n");
        }
    }

    private static String formatMiB(long bytes) {
        return String.format(Locale.ROOT, "%.2f MiB", bytes / 1048576.0d);
    }

    private static long measureTreeBytes(Path root) throws IOException {
        final long[] total = {0L};
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                total[0] += attrs.size();
                return FileVisitResult.CONTINUE;
            }
        });

        return total[0];
    }

    private static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Copy a classpath resource to disk. */
    private static void copyResource(String resource, File dst) throws IOException {
        try (InputStream in = Dumper.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IOException("Missing bundled resource: " + resource);
            File parent = dst.getParentFile();
            if (parent != null) parent.mkdirs();
            Files.copy(in, dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

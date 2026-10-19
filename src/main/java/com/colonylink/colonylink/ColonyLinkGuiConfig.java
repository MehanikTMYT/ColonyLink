package com.colonylink.colonylink;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.io.*;
import java.nio.file.Path;

/**
 * ColonyLink GUI visual config — persisted client-side only.
 * File: .minecraft/config/colonylink_gui.json
 *
 * Fields:
 *   bgColor      — ARGB of the GUI body (excluding the title bar)
 *   titleColor   — ARGB of the title bar
 *   borderColor  — ARGB of the borders
 *   borderWidth  — border thickness (1-4 px)
 *   opacity      — global GUI opacity (0.2-1.0, applied to bgColor + titleColor)
 *   scale        — GUI zoom factor (0.5-1.5)
 *
 * Colors are stored as int ARGB (0xAARRGGBB). Opacity scales the alpha channel
 * before rendering; scale is applied via PoseStack.scale() centered on the GUI.
 *
 * Singleton — access via ColonyLinkGuiConfig.get().
 */
public class ColonyLinkGuiConfig
{
    // Appearance theme
    // DEFAULT      = historical Clipboard look, driven by bg/title/border.
    // AE           = replica of the Warehouse Link Terminal look (palette from
    //                TerminalSkin/WarehouseLinkTerminalScreen).
    // MINECOLONIES = replica of the MineColonies "parchment" look (background blitted
    //                from minecolonies:textures/gui/citizen/colonist_paper.png at runtime).
    // Appearance-only: no behavior, packet or logic depends on it.
    public enum Theme { DEFAULT, AE, MINECOLONIES }

    // Defaults
    public static final int   DEFAULT_BG_COLOR     = 0xFF8B8B8B;
    public static final int   DEFAULT_TITLE_COLOR  = 0xFF6B6B6B;
    public static final int   DEFAULT_BORDER_COLOR = 0xFFFFFFFF;
    public static final int   DEFAULT_BORDER_WIDTH = 2;
    public static final float DEFAULT_OPACITY      = 1.0f;
    public static final float DEFAULT_SCALE        = 0.9f;
    public static final Theme DEFAULT_THEME        = Theme.DEFAULT;

    // Controls (buttons, tabs) keep a minimum alpha so they stay locatable when the
    // background is made very transparent. Effective control alpha = max(opacity, this).
    public static final float MIN_CONTROL_OPACITY  = 0.50f;

    // Fields (serialized by Gson)
    public int   bgColor     = DEFAULT_BG_COLOR;
    public int   titleColor  = DEFAULT_TITLE_COLOR;
    public int   borderColor = DEFAULT_BORDER_COLOR;
    public int   borderWidth = DEFAULT_BORDER_WIDTH;
    public float opacity     = DEFAULT_OPACITY;
    public float scale       = DEFAULT_SCALE;
    public Theme theme       = DEFAULT_THEME;

    // Singleton
    private static ColonyLinkGuiConfig INSTANCE = null;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "colonylink_gui.json";

    public static ColonyLinkGuiConfig get()
    {
        if (INSTANCE == null) INSTANCE = load();
        return INSTANCE;
    }

    public static void reload()
    {
        INSTANCE = load();
    }

    // Persistence

    private static Path configPath()
    {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve(FILE_NAME);
    }

    private static ColonyLinkGuiConfig load()
    {
        Path path = configPath();
        if (path.toFile().exists())
        {
            try (Reader r = new FileReader(path.toFile()))
            {
                ColonyLinkGuiConfig cfg = GSON.fromJson(r, ColonyLinkGuiConfig.class);
                if (cfg != null)
                {
                    cfg.clamp();
                    return cfg;
                }
            }
            catch (Exception e)
            {
                ColonyLink.LOGGER.warn("[ColonyLink] Failed to load GUI config, using defaults: {}", e.getMessage());
            }
        }
        ColonyLinkGuiConfig defaults = new ColonyLinkGuiConfig();
        defaults.save();
        return defaults;
    }

    public void save()
    {
        try
        {
            Path path = configPath();
            path.getParent().toFile().mkdirs();
            try (Writer w = new FileWriter(path.toFile()))
            {
                GSON.toJson(this, w);
            }
        }
        catch (Exception e)
        {
            ColonyLink.LOGGER.warn("[ColonyLink] Failed to save GUI config: {}", e.getMessage());
        }
    }

    /** Clamps all fields to their min/max bounds. */
    public void clamp()
    {
        borderWidth = Math.max(1, Math.min(4, borderWidth));
        opacity     = Math.max(0.1f, Math.min(1.0f, opacity));
        scale       = Math.max(0.5f, Math.min(1.5f, scale));
    }

    // AE palette — structural values from the Warehouse Link Terminal
    // (TerminalSkin.PAL_BG + C_* constants of WarehouseLinkTerminalScreen). Covers
    // ONLY the chrome (backgrounds, frames, bevels, neutral tabs, scrollbar,
    // separators, neutral icons). Semantic colors (green/red/blue/disabled-grey +
    // getWorkerStatusColor) NEVER go through here.
    // Values marked "eye-tune" are starting candidates (Phase C).
    private static final int AE_FRAME_BG      = 0xFFADB0C4; // PAL_BG[0]
    private static final int AE_TITLE_BG      = 0xFF878FA5; // PAL_BG[6]
    private static final int AE_TITLE_HI      = 0xFFCBCCD4; // PAL_BG[1]
    private static final int AE_BORDER_LIGHT  = 0xFFF2F2F2; // PAL_BG[3]
    private static final int AE_BORDER_DARK   = 0xFF413F54; // PAL_BG[4] — signature AE
    private static final int AE_TITLE_TEXT    = 0xFF2A2838; // eye-tune (sombre sur lavande)
    private static final int AE_WELL_BG       = 0xFFDDDEE3; // light well, slightly below the body (marks the recess)
    private static final int AE_WELL_LIGHT    = 0xFFF2F2F2; // PAL_BG[3] — subtle light bevel on the light body
    private static final int AE_WELL_DARK     = 0xFF878FA5; // PAL_BG[6] — subtle dark bevel on the light body
    private static final int AE_ROW_A         = 0xFFE8E9ED; // light row (near the body), subtle zebra
    private static final int AE_ROW_B         = 0xFFDDDEE3; // light row, slightly marked (= well tint)
    private static final int AE_SCROLL_TRACK  = 0xFF413F54; // PAL_BG[4]
    private static final int AE_SCROLL_THUMB  = 0xFFADB0C4; // PAL_BG[0]
    private static final int AE_SEPARATOR     = 0xFF696D88; // PAL_BG[5]
    private static final int AE_HANDLE        = 0xFF696D88; // PAL_BG[5]
    private static final int AE_HANDLE_HI     = 0xFF9A9FB4; // PAL_BG[2]
    private static final int AE_REQ_BG        = 0xFFDDDEE3; // light request well (matches AE_WELL_BG)
    private static final int AE_REQ_LIGHT     = 0xFFF2F2F2; // PAL_BG[3] — subtle light bevel on the light body
    private static final int AE_REQ_DARK      = 0xFF878FA5; // PAL_BG[6] — subtle dark bevel on the light body
    private static final int AE_BTN_LIGHT     = 0xFFF2F2F2; // PAL_BG[3]
    private static final int AE_BTN_DARK      = 0xFF413F54; // PAL_BG[4]
    private static final int AE_NEUTRAL_BG    = 0xFF696D88; // PAL_BG[5]
    private static final int AE_NEUTRAL_BG_HI = 0xFF878FA5; // PAL_BG[6]
    private static final int AE_TAB_INACT_BG    = 0xFF696D88; // PAL_BG[5]
    private static final int AE_TAB_INACT_LIGHT = 0xFF878FA5; // PAL_BG[6]
    private static final int AE_TAB_INACT_DARK  = 0xFF413F54; // PAL_BG[4]
    private static final int AE_TAB_ACTIVE_BG   = 0xFFCBCCD4; // PAL_BG[1]
    private static final int AE_ICON_NEUTRAL    = 0xFFCBCCD4; // PAL_BG[1]
    private static final int AE_ICON_DIM        = 0xFF9A9FB4; // PAL_BG[2]

    // AE thin frame — EXACT sequences from TerminalSkin.BG (decoded pixel-perfect on
    // 6 clean positions, excluding the central slot x=202..217). Outer → inner order.
    // Thickness: top/left/right = 2px, bottom = 4px. Top/left/right are IDENTICAL:
    // 0xFF413F54 → 0xFFF2F2F2. 0xCBCCD4 is NOT here (it's the inner field, handled by
    // the content palette). Corners stay sharp (the skin's alpha anti-aliasing can't
    // be cleanly reproduced with g.fill) — known limitation.
    private static final int[] AE_FRAME_TOP    = { 0xFF413F54, 0xFFF2F2F2 };
    private static final int[] AE_FRAME_LEFT   = { 0xFF413F54, 0xFFF2F2F2 };
    private static final int[] AE_FRAME_RIGHT  = { 0xFF413F54, 0xFFF2F2F2 };
    private static final int[] AE_FRAME_BOTTOM = { 0xFF413F54, 0xFF878FA5, 0xFF878FA5, 0xFFF2F2F2 };

    // Structural defaults for the DEFAULT theme (historical Clipboard values).
    // Exposed via accessors so the Default rendering stays bit-identical.
    private static final int DEF_TITLE_TEXT    = 0x404040;
    private static final int DEF_WELL_BG       = 0xFF3A3A3A;
    private static final int DEF_LIST_BG       = 0xFF373737;
    private static final int DEF_WELL_LIGHT    = 0xFF8B8B8B;
    private static final int DEF_WELL_DARK     = 0xFF373737;
    private static final int DEF_ROW_A         = 0xFF4A4A4A;
    private static final int DEF_ROW_B         = 0xFF424242;
    private static final int DEF_SCROLL_TRACK  = 0xFF373737;
    private static final int DEF_SCROLL_THUMB  = 0xFF8B8B8B;
    private static final int DEF_SEPARATOR     = 0xFF555555;
    private static final int DEF_HANDLE        = 0xFF888888;
    private static final int DEF_HANDLE_HI     = 0xFFCCCCCC;
    private static final int DEF_REQ_BG        = 0xFF2E2E4A;
    private static final int DEF_REQ_LIGHT     = 0xFF6666AA;
    private static final int DEF_REQ_DARK      = 0xFF1A1A3A;
    private static final int DEF_BTN_LIGHT     = 0xFFFFFFFF;
    private static final int DEF_BTN_DARK      = 0xFF373737;
    private static final int DEF_NEUTRAL_BG    = 0xFF404060;
    private static final int DEF_NEUTRAL_BG_HI = 0xFF505070;
    private static final int DEF_TAB_INACT_BG    = 0xFF4A4A4A;
    private static final int DEF_TAB_INACT_HOVER = 0xFF555555;
    private static final int DEF_TAB_INACT_LIGHT = 0xFF6B6B6B;
    private static final int DEF_TAB_INACT_DARK  = 0xFF222222;
    private static final int DEF_ICON_NEUTRAL    = 0xFFE0E0E0;
    private static final int DEF_ICON_DIM        = 0xFF888888;

    // AE SEMANTIC TINTS — status/label colors calibrated for the AE light body.
    // Named by MEANING (not usage) so they can be reused for colored button labels
    // (layer 4). These REPLACE the bright, dark-background-calibrated colors ONLY in
    // theme AE: the vivid greens/reds/yellows are unreadable on the light AE terminal
    // body, so each maps to a darker, still-recognizable tint (green stays green…).
    // In theme DEFAULT nothing uses these — the original bright colors are kept.
    private static final int AE_SEM_GREEN  = 0xFF0F7A26; // forest green, readable on light (Working, Craft, Linked)
    private static final int AE_SEM_RED    = 0xFFC01818; // dark red (Sick, Raided, Unlink, Unlinked)
    private static final int AE_SEM_BLUE   = 0xFF1E5AC8; // deep clear blue (Sleeping, accented info)
    private static final int AE_SEM_AMBER  = 0xFFB8860B; // dark amber, replaces unreadable yellow (Idle, No home)
    private static final int AE_SEM_ORANGE = 0xFFC85000; // dark orange (Hungry)
    private static final int AE_SEM_SLATE  = 0xFF4A6A99; // dark blue-grey (Bad weather)
    private static final int AE_SEM_GRAY   = 0xFF555555; // dark grey (Mourning, null, disabled)
    private static final int AE_BODY_TEXT  = 0xFF413F54; // normal text on the AE light body (= AE2 palette DEFAULT_TEXT)
    private static final int AE_MUTED_TEXT = 0xFF878FA5; // secondary / muted text (= AE2 palette MUTED)

    // MineColonies theme — body text
    // MineColonies GUIs draw pure-black text on the light parchment body (XML
    // color="black" → BlockUI Color 0xFF000000). Chrome/status colors are shared
    // with the AE light palette (see light() below); only the body text differs.
    private static final int MC_BODY_TEXT  = 0xFF000000; // pure black on parchment
    private static final int MC_MUTED_TEXT = 0xFF5A5040; // dark brown-grey secondary text, legible on cream parchment

    private boolean ae() { return theme == Theme.AE; }
    private boolean mc() { return theme == Theme.MINECOLONIES; }

    /**
     * True for any theme with a LIGHT window body (AE or MineColonies). These themes
     * share the light-calibrated chrome/status palette; DEFAULT keeps its dark palette
     * and bright status colors. Used so palette accessors branch light-vs-dark rather
     * than AE-vs-rest.
     */
    private boolean light() { return theme == Theme.AE || theme == Theme.MINECOLONIES; }

    /** True if the AE theme is active — for the Screen's bespoke AE-textured widgets. */
    public boolean isAe() { return ae(); }

    /** True if the MineColonies theme is active — for the Screen's parchment background. */
    public boolean isMineColonies() { return mc(); }

    /** True if the theme has a light body (AE or MineColonies) — dark text, light chrome. */
    public boolean isLightBody() { return light(); }

    // Color helpers

    /** Background alpha factor (0..1): the raw slider opacity, 10%-100%. */
    public float alphaBackground() { return opacity; }

    /** Control alpha factor: same slider, floored so buttons/tabs stay visible. */
    public float alphaControl() { return Math.max(opacity, MIN_CONTROL_OPACITY); }

    /** Scales an ARGB's alpha byte by the given 0..1 factor. */
    private static int applyAlpha(int argb, float factor)
    {
        int a  = (argb >> 24) & 0xFF;
        int a2 = (int)(a * factor);
        return (a2 << 24) | (argb & 0x00FFFFFF);
    }

    /**
     * Applies the configured opacity to an ARGB color (background category, full opacity).
     * Kept for existing callers; equivalent to {@link #applyBackground(int)}.
     */
    public int applyOpacity(int argb) { return applyAlpha(argb, alphaBackground()); }

    /** Background-category alpha (full opacity). */
    public int applyBackground(int argb) { return applyAlpha(argb, alphaBackground()); }

    /** Control-category alpha (floored at MIN_CONTROL_OPACITY). */
    public int applyControl(int argb) { return applyAlpha(argb, alphaControl()); }

    // Main chrome (theme-aware, opacity applied)

    /** GUI background — themed, with opacity. */
    public int bg()    { return applyOpacity(ae() ? AE_FRAME_BG   : bgColor); }

    /** Title bar — themed, with opacity. */
    public int title() { return applyOpacity(ae() ? AE_TITLE_BG   : titleColor); }

    /** Light border (top/left) — themed, with opacity. */
    public int border() { return applyOpacity(ae() ? AE_BORDER_LIGHT : borderColor); }

    /** Border "shadow" color (dark side). In AE: fixed palette value. */
    public int borderShadow()
    {
        if (ae()) return applyOpacity(AE_BORDER_DARK);
        int c = applyOpacity(borderColor);
        int r = Math.max(0, ((c >> 16) & 0xFF) / 2);
        int gv = Math.max(0, ((c >> 8)  & 0xFF) / 2);
        int b2 = Math.max(0, ( c        & 0xFF) / 2);
        int a  = (c >> 24) & 0xFF;
        return (a << 24) | (r << 16) | (gv << 8) | b2;
    }

    /**
     * Effective border width. In AE the frame defines its own (pixel-perfect)
     * geometry → fixed width independent of the slider (locked in config).
     */
    public int frameBorderWidth() { return ae() ? 2 : borderWidth; }

    // AE frame edge sequences (outer → inner, read-only)
    public int[] aeFrameTop()    { return AE_FRAME_TOP; }
    public int[] aeFrameLeft()   { return AE_FRAME_LEFT; }
    public int[] aeFrameRight()  { return AE_FRAME_RIGHT; }
    public int[] aeFrameBottom() { return AE_FRAME_BOTTOM; }

    // Structural palette accessors
    // Return the RAW theme-correct color (no opacity). Callers (ColonyLinkScreen)
    // wrap with applyOpacity() where the original code did, keeping the Default
    // rendering identical.

    /** Light edge at the top of the title bar (raw). */
    public int titleHi()      { return ae() ? AE_TITLE_HI   : lighten(titleColor, 1.3f); }
    /** Title text color (raw). */
    public int titleText()    { return ae() ? AE_TITLE_TEXT : DEF_TITLE_TEXT; }

    /** Active tab — background (raw). Default = lightened bgColor. */
    public int tabActiveBg()  { return ae() ? AE_TAB_ACTIVE_BG : (lighten(bgColor, 1.1f) | 0xFF000000); }

    // Content panels/lists sit BEHIND body text → they follow the light body (AE +
    // MineColonies share the AE light tints) so black/dark text stays legible.
    // DEFAULT keeps its dark tints unchanged (light() == false).
    public int wellBg()       { return light() ? AE_WELL_BG      : DEF_WELL_BG; }
    /** List panel background (0xFF373737 in Default ≠ wellBg 0xFF3A3A3A). */
    public int listBg()       { return light() ? AE_WELL_BG      : DEF_LIST_BG; }
    public int wellLight()    { return light() ? AE_WELL_LIGHT   : DEF_WELL_LIGHT; }
    public int wellDark()     { return light() ? AE_WELL_DARK    : DEF_WELL_DARK; }

    public int rowA()         { return light() ? AE_ROW_A        : DEF_ROW_A; }
    public int rowB()         { return light() ? AE_ROW_B        : DEF_ROW_B; }

    public int scrollTrack()  { return light() ? AE_SCROLL_TRACK : DEF_SCROLL_TRACK; }
    public int scrollThumb()  { return light() ? AE_SCROLL_THUMB : DEF_SCROLL_THUMB; }

    public int separator()    { return light() ? AE_SEPARATOR    : DEF_SEPARATOR; }

    /** Drag handle dots (raw). {@code hover} → light variant. */
    public int handleDot(boolean hover)
    { return hover ? (ae() ? AE_HANDLE_HI : DEF_HANDLE_HI) : (ae() ? AE_HANDLE : DEF_HANDLE); }

    public int reqBg()        { return light() ? AE_REQ_BG    : DEF_REQ_BG; }
    public int reqLight()     { return light() ? AE_REQ_LIGHT : DEF_REQ_LIGHT; }
    public int reqDark()      { return light() ? AE_REQ_DARK  : DEF_REQ_DARK; }

    /** Light/dark button bevel (chrome, not the semantic face). */
    public int btnBevelLight() { return ae() ? AE_BTN_LIGHT : DEF_BTN_LIGHT; }
    public int btnBevelDark()  { return ae() ? AE_BTN_DARK  : DEF_BTN_DARK; }

    /** Neutral button background (config, etc.). {@code hover} → light variant. */
    public int neutralBtnBg(boolean hover)
    { return hover ? (ae() ? AE_NEUTRAL_BG_HI : DEF_NEUTRAL_BG_HI) : (ae() ? AE_NEUTRAL_BG : DEF_NEUTRAL_BG); }

    public int tabInactiveBg()    { return ae() ? AE_TAB_INACT_BG    : DEF_TAB_INACT_BG; }
    /** Inactive tab with hover state (Citizens tab). */
    public int tabInactiveBg(boolean hover)
    { return hover ? (ae() ? AE_TAB_INACT_LIGHT : DEF_TAB_INACT_HOVER) : (ae() ? AE_TAB_INACT_BG : DEF_TAB_INACT_BG); }
    public int tabInactiveLight() { return ae() ? AE_TAB_INACT_LIGHT : DEF_TAB_INACT_LIGHT; }
    public int tabInactiveDark()  { return ae() ? AE_TAB_INACT_DARK  : DEF_TAB_INACT_DARK; }

    /** Neutral icons (gear/person/config). {@code dim} → dimmed variant. */
    public int iconNeutral(boolean dim)
    { return dim ? (ae() ? AE_ICON_DIM : DEF_ICON_DIM) : (ae() ? AE_ICON_NEUTRAL : DEF_ICON_NEUTRAL); }

    // AE semantic text tints (raw AE constants; callers guard with isAe() and
    //    supply the DEFAULT-theme color themselves, so the DEFAULT rendering is
    //    untouched). bodyText() is fully theme-aware (its DEFAULT is always white).
    public int semGreen()  { return AE_SEM_GREEN; }
    public int semRed()    { return AE_SEM_RED; }
    public int semBlue()   { return AE_SEM_BLUE; }
    public int semAmber()  { return AE_SEM_AMBER; }
    public int semOrange() { return AE_SEM_ORANGE; }
    public int semSlate()  { return AE_SEM_SLATE; }
    public int semGray()   { return AE_SEM_GRAY; }
    /** Normal body text: pure black on the MineColonies parchment, AE-dark on the AE
     *  light body, white in DEFAULT. */
    public int bodyText()  { return mc() ? MC_BODY_TEXT : (ae() ? AE_BODY_TEXT : 0xFFFFFFFF); }
    /** Muted/secondary text tint for the AE light body (raw; guard with isAe()). */
    public int mutedText() { return mc() ? MC_MUTED_TEXT : AE_MUTED_TEXT; }

    /** Lightens an ARGB color by a factor (local helper, cf. ColonyLinkScreen). */
    private static int lighten(int argb, float f)
    {
        int a  = (argb >> 24) & 0xFF;
        int r  = Math.min(255, (int)(((argb >> 16) & 0xFF) * f));
        int gv = Math.min(255, (int)(((argb >> 8)  & 0xFF) * f));
        int b  = Math.min(255, (int)(( argb        & 0xFF) * f));
        return (a << 24) | (r << 16) | (gv << 8) | b;
    }

    /**
     * Draws the GUI borders per config/theme.
     * Replaces the 4 hardcoded fills (light top/left, dark bottom/right).
     * In AE, {@link #border()}/{@link #borderShadow()}/{@link #frameBorderWidth()}
     * already return palette values → AE frame without changing this code.
     */
    public void drawBorders(net.minecraft.client.gui.GuiGraphics g, int x, int y, int w, int h)
    {
        int light  = border();
        int shadow = borderShadow();
        int bw     = frameBorderWidth();

        g.fill(x, y, x + w, y + bw, light);
        g.fill(x, y, x + bw, y + h, light);
        g.fill(x, y + h - bw, x + w, y + h, shadow);
        g.fill(x + w - bw, y, x + w, y + h, shadow);
    }

    // Reset

    public void reset()
    {
        bgColor     = DEFAULT_BG_COLOR;
        titleColor  = DEFAULT_TITLE_COLOR;
        borderColor = DEFAULT_BORDER_COLOR;
        borderWidth = DEFAULT_BORDER_WIDTH;
        opacity     = DEFAULT_OPACITY;
        scale       = DEFAULT_SCALE;
    }
}
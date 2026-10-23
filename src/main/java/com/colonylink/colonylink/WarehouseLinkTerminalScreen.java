package com.colonylink.colonylink;

import net.minecraft.client.renderer.RenderPipelines;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import com.ldtteam.domumornamentum.block.IMateriallyTexturedBlock;
import com.ldtteam.domumornamentum.block.IMateriallyTexturedBlockComponent;
import com.ldtteam.domumornamentum.client.model.data.MaterialTextureData;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;

import java.util.*;

import static com.colonylink.colonylink.WarehouseLinkTerminalMenu.*;

/**
 * WarehouseLinkTerminalScreen — v2.0.0
 *
 * Full redesign based on the background PNG warehouse_terminalcolony.png (420×432 px).
 *
 * Textures:
 *   colonylink:textures/gui/warehouse_terminalcolony.png  — main background
 *   colonylink:textures/button/button_1.png               — normal button  (12×12)
 *   colonylink:textures/button/button_pushed.png          — pushed button  (12×12)
 *   colonylink:textures/button/checkbox_wh.png            — WH-active slider (22×12)
 *   colonylink:textures/button/checkbox_ae.png            — AE-active slider (22×12)
 *   colonylink:textures/button/slider.png                 — scrollbar thumb (13×16)
 *
 * Layout:
 *   WH panel: x=8..175,  y=31..227  (9 cols × 11 rows, 18px pitch)
 *   AE panel: x=233..400, y=31..227
 *   Card slot: x=202..217, y=2..17
 *   3×3 grid: x=147..200, y=273..326
 *   Output: x=253..274, y=289..310
 *   Inventory: x=130..289, y=348..401  (3×9)
 *   Hotbar: x=130..289, y=406..423
 *
 * Vertical WH scrollbar: x=176..185, thumb start y=33, end y=227
 * Vertical AE scrollbar: x=401..410, thumb start y=33, end y=227
 * Horizontal WH/ME slider: x=189..229, y=241..254 (checkbox 22×12)
 * Push buttons ×2: (204,32) and (204,47), size 11×11 (cropped from 12×12)
 * Cross buttons:
 *   7 clear→inv  : (136,273) 7×7
 *   8 result→WH  : (252,279) 7×7
 *   9 result→ME  : (268,279) 7×7
 *  10 result→inv : (260,313) 7×7
 */
public class WarehouseLinkTerminalScreen extends AbstractContainerScreen<WarehouseLinkTerminalMenu>
{
    // ── Textures ──────────────────────────────────────────────────────────────
    // Chrome (fond, boutons, onglets, slider, checkbox, overlay Cutter) rendu 100%
    // en code via TerminalSkin — pixel-perfect, sans texture. Seules les 2 icônes
    // d'onglet restent en PNG.
    private static final Identifier TEX_ICO_CRAFT =
            Identifier.fromNamespaceAndPath("colonylink", "textures/button/craft_ico.png");
    private static final Identifier TEX_ICO_CUTTER =
            Identifier.fromNamespaceAndPath("colonylink", "textures/button/cutter_ico.png");

    // Text / overlay colors
    private static final int C_TEXT_WH      = 0xFF9A9FB4;
    private static final int C_TEXT_AE      = 0xFF6699FF;
    private static final int C_TEXT_MUTED   = 0xFF9A9FB4;
    private static final int C_SF_TEXT      = 0xFFCCCDFF;
    private static final int C_SF_PH        = 0xFF555577;
    private static final int C_HOVER_SL     = 0x5500CCFF;
    private static final int C_SELECTED     = 0x8800AAFF;
    private static final int C_CRAFTABLE_DOT= 0xFF00FF88;
    private static final int C_CRAFTONLY_OV = 0x4400AAFF;
    private static final int C_OFFLINE_OV   = 0xBB220000;
    private static final int C_NOCARD_OV    = 0xBB111122;
    private static final int C_COUNT_TEXT   = 0xFFFFFFFF;
    private static final int C_COUNT_SHADOW = 0xFF3F3F3F;
    private static final float COUNT_FONT_SCALE = 0.72f;

    // State
    private final List<WarehouseTerminalSyncPacket.WarehouseItemEntry> whItems    = new ArrayList<>();
    private final List<WarehouseTerminalSyncPacket.WarehouseItemEntry> whFiltered = new ArrayList<>();
    private final List<MeItemEntry> meItems = new ArrayList<>(), meFiltered = new ArrayList<>();

    private boolean hasWarehouseCard = false;
    private String  whErrorMsg = "";

    private static String savedWhSearch = "";
    private static String savedMeSearch = "";
    private boolean closedManually = false;

    private String  whSearch = "", meSearch = "";
    private boolean whSearchFocused = false, meSearchFocused = false;
    private int     whScroll = 0, meScroll = 0;

    private boolean warehouseFirst = true;

    private final Set<Integer> whSelected = new LinkedHashSet<>();
    private final Set<Integer> meSelected = new LinkedHashSet<>();
    private boolean shiftWasDown = false;

    // v1.4.2 — Tabs
    // Tab 0 = Crafting Table (default), Tab 1 = Domum Cutter
    private static final int TAB_X        = 295;
    private static final int TAB_CRAFT_Y  = 269;
    private static final int TAB_CUTTER_Y = 291;
    private static final int TAB_W        = 22;
    private static final int TAB_H        = 22;
    private static final int ICO_SIZE     = 16;
    private static final int ICO_CRAFT_X  = TAB_X + 3;
    private static final int ICO_CRAFT_Y  = TAB_CRAFT_Y + 3;
    private static final int ICO_CUT_X    = TAB_X + 3;
    private static final int ICO_CUT_Y    = TAB_CUTTER_Y + 3;

    // pattern_gui.png overlay coords
    private static final int PG_X = 129;
    private static final int PG_Y = 266;
    private static final int PG_W = 166;
    private static final int PG_H = 68;

    // Domum slots — visual coords (real slots at -2000 in the Menu)
    private static final int DOMUM_TARGET_RENDER_X = 139;
    private static final int DOMUM_TARGET_RENDER_Y = 283;
    private static final int MAT_START_X = 162;
    private static final int MAT_Y       = 283;
    private static final int MAT_PITCH   = 18;
    private static final int MAT_MAX     = 4;
    private static final int BLANK_RENDER_X  = 269;
    private static final int BLANK_RENDER_Y  = 267;
    private static final int ENCODE_BTN_X    = 271;
    private static final int ENCODE_BTN_Y    = 292;
    private static final int ENCODE_BTN_W    = 12;
    private static final int ENCODE_BTN_H    = 12;
    private static final int OUTPUT_RENDER_X = 269;
    private static final int OUTPUT_RENDER_Y = 314;

    private int     activeTab = 0;
    private boolean encodePushed = false;
    private final List<ItemStack> cachedMaterials = new ArrayList<>();
    private ItemStack lastTargetForCache = ItemStack.EMPTY;

    // Domum queue (received via DomumQueueSyncPacket)
    private final List<ItemStack> domumQueue     = new ArrayList<>();
    private int                   domumQueueScroll    = 0;
    private int                   domumQueueSelected  = -1; // selected index

    // Cutter tab layout (coords relative to the PG_X/PG_Y panel)
    // Reference item slot (manually placed)
    private static final int CUT_REF_X     = 3;
    private static final int CUT_REF_Y     = 26;
    // Material preview 1
    private static final int CUT_MAT1_X    = 3;
    private static final int CUT_MAT1_Y    = 5;
    // Material preview 2
    private static final int CUT_MAT2_X    = 3;
    private static final int CUT_MAT2_Y    = 46;
    // Scrollable list area (96 wide, 3px right margin before panel edge)
    private static final int CUT_LIST_X    = 25;
    private static final int CUT_LIST_Y    = 3;
    private static final int CUT_LIST_W    = 96;
    private static final int CUT_LIST_H    = 63;
    private static final int CUT_ROW_H     = 18;
    private static final int CUT_ROWS_VIS  = CUT_LIST_H / CUT_ROW_H; // = 3

    // Scrollbar drag state
    private boolean draggingWh = false, draggingAe = false;
    private int     dragStartY = 0, dragStartScroll = 0;
    private int     dragStartSlotWh = -1, dragStartSlotAe = -1; // slot where the drag started

    // Push-button animation state
    private boolean btn1Pushed = false, btn2Pushed = false;
    private int[] origSlotY;   // original Y of vanilla slots (captured once, resize-safe)

    private String hoveredTooltip = null;

    // Fit-to-window scale

    // Constructor
    public WarehouseLinkTerminalScreen(WarehouseLinkTerminalMenu menu,
                                       Inventory inv, Component title)
    {
        // PORT(26.1): image size is final and passed to the constructor; the fluid height lives in fluidHeight.
        super(menu, inv, title, GUI_W, GUI_H);
    }

    /** Hauteur réelle du panneau (fluide selon la fenêtre) — imageHeight est final en 26.1. */
    private int fluidHeight = GUI_H;

    @Override
    protected boolean hasClickedOutside(double mx, double my, int xo, int yo)
    {
        return mx < xo || my < yo || mx >= xo + imageWidth || my >= yo + fluidHeight;
    }

    @Override protected void init()
    {
        // 1.7.0-beta.5 (audit #10) — the menu tolerates part == null on the client (host
        // block entity not resolvable client-side when the menu opened: chunk not yet
        // loaded). Every panel of this screen needs the Part; instead of NPE-crashing
        // the client, tell the player and close cleanly (the server menu closes with it,
        // the player simply reopens the terminal).
        if (menu.getPart() == null)
        {
            if (minecraft != null && minecraft.player != null)
                minecraft.player.sendSystemMessage(Component.translatable("colonylink.terminal.part_unavailable"));
            this.onClose();
            return;
        }
        fluidHeight = (GUI_H - PANEL_ROWS * SLOT_PITCH) + visRows() * SLOT_PITCH; // hauteur fluide
        super.init();
        topPos = (this.height - fluidHeight) / 2; // re-centre sur la hauteur fluide
        titleLabelX = inventoryLabelX = -10000;
        // Shift vanilla slots (craft + output + inventory) up by rowDY() px when rows are
        // removed (AE2 style). Capture original Y once (resize-safe).
        if (origSlotY == null)
        {
            origSlotY = new int[menu.slots.size()];
            for (int i = 0; i < menu.slots.size(); i++) origSlotY[i] = menu.slots.get(i).y;
        }
        int slotDY  = rowDY();
        int slotCut = Y_ITEMS + PANEL_ROWS * SLOT_PITCH; // 229 — bottom-block threshold
        for (int i = 0; i < menu.slots.size(); i++)
        {
            int oy = origSlotY[i];
            setSlotY(menu.slots.get(i), (oy >= slotCut) ? oy - slotDY : oy);
        }
        whSearch = savedWhSearch;
        meSearch = savedMeSearch;
        rebuildWh();
        rebuildMe();
        whScroll = clamp(whScroll, whFiltered.size()); // re-clamp after visRows change (resize)
        meScroll = clamp(meScroll, meFiltered.size());
        WarehouseLinkTerminalMenu.warehouseFirst = warehouseFirst;
        ClientPacketDistributor.sendToServer(new TerminalGuiStatePacket(true, partHostPos(), partSide()));
    }

    @Override public void onClose()
    {
        closedManually = true;
        savedWhSearch = "";
        savedMeSearch = "";
        ClientPacketDistributor.sendToServer(new TerminalGuiStatePacket(false, partHostPos(), partSide()));
        super.onClose();
    }

    @Override public void removed()
    {
        if (!closedManually)
        {
            savedWhSearch = whSearch;
            savedMeSearch = meSearch;
        }
        super.removed();
    }

    // Updates from packets
    public void updateWarehouseSnapshot(WarehouseTerminalSyncPacket p)
    {
        whItems.clear(); whItems.addAll(p.entries());
        hasWarehouseCard = p.hasWarehouseCard();
        whErrorMsg = p.hasError() ? p.errorMessage() : "";
        rebuildWh();
    }

    public void updateMeSnapshot(TerminalMeSyncPacket p)
    {
        meItems.clear();
        for (var e : p.entries())
            meItems.add(new MeItemEntry(e.stack(), e.count(), e.craftable()));
        rebuildMe();
    }

    /** Received via DomumQueueSyncPacket — updates the client-side list. */
    public void updateDomumQueue(java.util.List<ItemStack> queue)
    {
        domumQueue.clear();
        domumQueue.addAll(queue);
        // Reset the selection if it pointed at an index now out of range
        if (domumQueueSelected >= domumQueue.size()) domumQueueSelected = -1;
        domumQueueScroll = Math.max(0, Math.min(domumQueueScroll,
                Math.max(0, domumQueue.size() - CUT_ROWS_VIS)));
    }

    // SEARCH — shared WH + ME filter (client only). AE2-inspired syntax:
    //   @mod  → the mod id contains the term (e.g. @ae2, @minecolonies)
    //   #tag  → an item tag contains the term (e.g. #logs, #ingots)
    //   text  → the display name contains the term
    // Multiple terms = AND (all must match), like AE2.
    private static boolean matchesQuery(net.minecraft.world.item.ItemStack stack, String query)
    {
        if (query.isEmpty()) return true;
        String name = stack.getDisplayName().getString().toLowerCase();
        String modId = null; // computed on demand
        for (String term : query.split("\\s+"))
        {
            if (term.isEmpty()) continue;
            boolean ok;
            if (term.charAt(0) == '@')
            {
                String needle = term.substring(1);
                if (needle.isEmpty()) { ok = true; }
                else
                {
                    if (modId == null)
                        modId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                                .getKey(stack.getItem()).getNamespace().toLowerCase();
                    ok = modId.contains(needle);
                }
            }
            else if (term.charAt(0) == '#')
            {
                String needle = term.substring(1);
                if (needle.isEmpty()) { ok = true; }
                else
                    ok = stack.typeHolder().tags().anyMatch(t ->
                            t.location().getPath().contains(needle)
                                    || t.location().toString().contains(needle));
            }
            else
            {
                ok = name.contains(term);
            }
            if (!ok) return false; // AND: every term must match
        }
        return true;
    }

    private void rebuildWh()
    {
        whFiltered.clear();
        String q = whSearch.toLowerCase();
        for (var e : whItems)
            if (matchesQuery(e.stack(), q))
                whFiltered.add(e);
        whScroll = clamp(whScroll, whFiltered.size());
    }

    private void rebuildMe()
    {
        meFiltered.clear();
        String q = meSearch.toLowerCase();
        for (var e : meItems)
            if (matchesQuery(e.stack(), q))
                meFiltered.add(e);
        meScroll = clamp(meScroll, meFiltered.size());
    }

    // =========================================================================
    // RENDER PRINCIPAL
    // =========================================================================
    @Override public void extractRenderState(GuiGraphicsExtractor g, int rawMx, int rawMy, float pt)
    {
        hoveredTooltip = null;
        boolean shiftNow = this.minecraft.hasShiftDown();
        if (shiftWasDown && !shiftNow) { whSelected.clear(); meSelected.clear(); }
        shiftWasDown = shiftNow;

        int mx = rawMx;
        int my = rawMy;



        super.extractRenderState(g, mx, my, pt);
        renderPanelItems(g, leftPos + X_ITEMS_WH, topPos, true,  mx, my);
        renderPanelItems(g, leftPos + X_ITEMS_AE, topPos, false, mx, my);


        // Tooltips à la position brute du curseur (taille réelle, placement correct)
        extractTooltip(g, rawMx, rawMy);
        renderTooltipForPanel(g, mx, my, rawMx, rawMy);

        if (hoveredTooltip != null)
        {
            String[] lines = hoveredTooltip.split("\n");
            if (lines.length == 1)
                g.setTooltipForNextFrame(font, Component.literal(hoveredTooltip), rawMx, rawMy);
            else
            {
                List<Component> ttLines = new ArrayList<>();
                for (String line : lines) ttLines.add(Component.literal(line));
                g.setComponentTooltipForNextFrame(font, ttLines, rawMx, rawMy);
            }
        }
    }

    @Override protected void extractLabels(GuiGraphicsExtractor g, int mx, int my) {}

    // renderBg: background + all widgets
    // Fluid AE2-style layout: visible rows depend on window height
    private int visRows()
    {
        int fixedH = GUI_H - PANEL_ROWS * SLOT_PITCH;   // height excluding rows (= 234)
        int n = (this.height - fixedH) / SLOT_PITCH;
        return Math.max(2, Math.min(PANEL_ROWS, n));
    }
    private int rowDY() { return (PANEL_ROWS - visRows()) * SLOT_PITCH; }

    // Slot.x/y are final in 1.21.1 -> reposition via reflection (Mojmap field "y",
    // valid in dev and NeoForge prod). Vanilla render and hit-test then use the
    // updated position automatically.
    private static java.lang.reflect.Field SLOT_Y;
    private static void setSlotY(net.minecraft.world.inventory.Slot slot, int y)
    {
        try
        {
            if (SLOT_Y == null)
            {
                SLOT_Y = net.minecraft.world.inventory.Slot.class.getDeclaredField("y");
                SLOT_Y.setAccessible(true);
            }
            SLOT_Y.setInt(slot, y);
        }
        catch (ReflectiveOperationException e)
        {
            throw new RuntimeException("ColonyLink: impossible de repositionner le slot", e);
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float pt)
    {
        super.extractBackground(g, mx, my, pt);
        // PORT(26.1): the veil used to be filled in render() before the background; backgrounds are now
        // extracted before render state, so it moves here to stay underneath the panel.
        g.fill(0, 0, this.width, this.height, 0xD8000000);
        int x = leftPos, y = topPos;
        int dy     = rowDY();
        int yb     = y - dy;                              // base for bottom-anchored elements
        int cutTop = Y_ITEMS + visRows() * SLOT_PITCH;    // local coords of the BG skin
        int cutBot = Y_ITEMS + PANEL_ROWS * SLOT_PITCH;   // = 229

        // 1. Main background (full PNG texture)
        TerminalSkin.drawCut(g, TerminalSkin.BG, x, y, cutTop, cutBot, dy);

        // 2. WH / AE text labels (above the search bars)
        renderPanelLabels(g, x, y);

        // 3. Search bars
        renderSearch(g, x + SEARCH_WH_X, y + SEARCH_Y, SEARCH_WH_W, SEARCH_H,
                whSearch, whSearchFocused, whFiltered.size(), true);
        renderSearch(g, x + SEARCH_AE_X, y + SEARCH_Y, SEARCH_AE_W, SEARCH_H,
                meSearch, meSearchFocused, meFiltered.size(), false);

        // 4. Vertical scrollbars
        renderScrollbar(g, x + SCROLL_WH_X, y, whFiltered.size(), whScroll);
        renderScrollbar(g, x + SCROLL_AE_X, y, meFiltered.size(), meScroll);

        // 5. Horizontal WH/ME slider
        renderSlider(g, x, yb, mx, my);

        // 6. Center-column push buttons — shown in BOTH tabs: the WH/ME panels and
        //    their transfers stay active in Craft as in Cutter.
        renderPushButton(g, mx, my, x + BTN1_X, y + BTN1_Y, btn1Pushed,
                Component.translatable("colonylink.term.btn_transfer_wh_me").getString());
        renderPushButton(g, mx, my, x + BTN2_X, y + BTN2_Y, btn2Pushed,
                Component.translatable("colonylink.term.btn_transfer_inv").getString());

        // 7. Craft-grid cross-button tooltips (Craft tab only)
        if (activeTab == 0)
        {
            if (over(mx, my, x + 136, yb + 273, 7, 7))
                hoveredTooltip = Component.translatable("colonylink.term.clear_grid").getString();
            if (over(mx, my, x + 252, yb + 279, 7, 7))
                hoveredTooltip = Component.translatable("colonylink.term.output_wh").getString();
            if (over(mx, my, x + 268, yb + 279, 8, 7))
                hoveredTooltip = Component.translatable("colonylink.term.output_me").getString();
            if (over(mx, my, x + 260, yb + 313, 7, 7))
                hoveredTooltip = Component.translatable("colonylink.term.output_inv").getString();
        }

        // 8. Selection count on panels
        if (!whSelected.isEmpty())
            GuiCompat.drawString(g, font, Component.translatable("colonylink.term.selected", whSelected.size()).getString(),
                    x + 136, y + 15, 0xFFFFDD00, false);
        if (!meSelected.isEmpty())
            GuiCompat.drawString(g, font, Component.translatable("colonylink.term.selected", meSelected.size()).getString(),
                    x + 361, y + 15, 0xFFFFDD00, false);

        // 9. v1.4.2 — Tabs
        renderTabs(g, x, yb, mx, my);

        // 10. v1.4.2 — Cutter zone (overlaid when tab 1 active)
        if (activeTab == 1)
            renderCutterOverlay(g, x, yb, mx, my);

        // 11. Warehouse Link Card slot tooltip (when empty)
        if (!hasWarehouseCard)
            if (over(mx, my, x + WarehouseLinkTerminalMenu.CARD_SLOT_X,
                    y + WarehouseLinkTerminalMenu.CARD_SLOT_Y, 16, 16))
                hoveredTooltip = Component.translatable("colonylink.term.card_slot_tip").getString();
    }

    // ── Onglets (x=295, y=269 craft / y=291 cutter) ───────────────────────────
    private void renderTabs(GuiGraphicsExtractor g, int x, int y, int mx, int my)
    {
        boolean craftActive = (activeTab == 0);
        TerminalSkin.draw(g, craftActive ? TerminalSkin.TAB_ON : TerminalSkin.TAB_OFF,
                x + TAB_X, y + TAB_CRAFT_Y);
        g.blit(RenderPipelines.GUI_TEXTURED, TEX_ICO_CRAFT,
                x + ICO_CRAFT_X, y + ICO_CRAFT_Y, 0, 0, ICO_SIZE, ICO_SIZE, ICO_SIZE, ICO_SIZE);
        if (over(mx, my, x + TAB_X, y + TAB_CRAFT_Y, TAB_W, TAB_H))
            hoveredTooltip = Component.translatable("colonylink.term.tab_craft").getString();

        boolean cutActive = (activeTab == 1);
        TerminalSkin.draw(g, cutActive ? TerminalSkin.TAB_ON : TerminalSkin.TAB_OFF,
                x + TAB_X, y + TAB_CUTTER_Y);
        g.blit(RenderPipelines.GUI_TEXTURED, TEX_ICO_CUTTER,
                x + ICO_CUT_X, y + ICO_CUT_Y, 0, 0, ICO_SIZE, ICO_SIZE, ICO_SIZE, ICO_SIZE);
        if (over(mx, my, x + TAB_X, y + TAB_CUTTER_Y, TAB_W, TAB_H))
            hoveredTooltip = Component.translatable("colonylink.term.tab_cutter").getString();
    }

    // ── Overlay Cutter ────────────────────────────────────────────────────────
    private void renderCutterOverlay(GuiGraphicsExtractor g, int x, int y, int mx, int my)
    {
        TerminalSkin.draw(g, TerminalSkin.PATTERN, x + PG_X, y + PG_Y);

        // Cutter panel origin (top-left corner of the PG overlay)
        int ox = x + PG_X;
        int oy = y + PG_Y;

        // Reference item slot (x3 y26)
        renderSlotOutline(g, ox + CUT_REF_X - 1, oy + CUT_REF_Y - 1);
        ItemStack target = menu.getDomumTarget();
        if (!target.isEmpty())
        {
            g.item(target, ox + CUT_REF_X, oy + CUT_REF_Y);
            g.itemDecorations(font, target, ox + CUT_REF_X, oy + CUT_REF_Y);
            if (over(mx, my, ox + CUT_REF_X, oy + CUT_REF_Y, 16, 16))
                hoveredTooltip = Component.translatable("colonylink.term.ref_model", target.getDisplayName()).getString();
            updateMaterialCache(target);
        }
        else
        {
            if (over(mx, my, ox + CUT_REF_X, oy + CUT_REF_Y, 16, 16))
                hoveredTooltip = Component.translatable("colonylink.term.preview_domum").getString();
            cachedMaterials.clear();
            lastTargetForCache = ItemStack.EMPTY;
        }

        // Material preview 1 (x3 y5)
        renderSlotOutline(g, ox + CUT_MAT1_X - 1, oy + CUT_MAT1_Y - 1);
        if (cachedMaterials.size() >= 1 && !cachedMaterials.get(0).isEmpty())
        {
            ItemStack mat1 = cachedMaterials.get(0);
            g.item(mat1, ox + CUT_MAT1_X, oy + CUT_MAT1_Y);
            if (over(mx, my, ox + CUT_MAT1_X, oy + CUT_MAT1_Y, 16, 16))
                hoveredTooltip = Component.translatable("colonylink.term.material1", mat1.getDisplayName()).getString();
        }
        else if (over(mx, my, ox + CUT_MAT1_X, oy + CUT_MAT1_Y, 16, 16))
            hoveredTooltip = Component.translatable("colonylink.term.material_slot1").getString();

        // Material preview 2 (x3 y46)
        renderSlotOutline(g, ox + CUT_MAT2_X - 1, oy + CUT_MAT2_Y - 1);
        if (cachedMaterials.size() >= 2 && !cachedMaterials.get(1).isEmpty())
        {
            ItemStack mat2 = cachedMaterials.get(1);
            g.item(mat2, ox + CUT_MAT2_X, oy + CUT_MAT2_Y);
            if (over(mx, my, ox + CUT_MAT2_X, oy + CUT_MAT2_Y, 16, 16))
                hoveredTooltip = Component.translatable("colonylink.term.material2", mat2.getDisplayName()).getString();
        }
        else if (over(mx, my, ox + CUT_MAT2_X, oy + CUT_MAT2_Y, 16, 16))
            hoveredTooltip = Component.translatable("colonylink.term.material_slot2").getString();

        // Blank Pattern (reuses existing BLANK_RENDER_X/Y)
        ItemStack blank = menu.getSlot(BLANK_PATTERN_SLOT).getItem();
        renderSlotOutline(g, x + BLANK_RENDER_X - 1, y + BLANK_RENDER_Y - 1);
        if (!blank.isEmpty())
        {
            g.item(blank, x + BLANK_RENDER_X, y + BLANK_RENDER_Y);
            g.itemDecorations(font, blank, x + BLANK_RENDER_X, y + BLANK_RENDER_Y);
        }
        if (over(mx, my, x + BLANK_RENDER_X, y + BLANK_RENDER_Y, 16, 16))
            hoveredTooltip = blank.isEmpty() ? Component.translatable("colonylink.term.insert_blank").getString() : blank.getDisplayName().getString();

        // Output slot (reuses existing OUTPUT_RENDER_X/Y)
        ItemStack output = menu.getDomumOutput();
        renderSlotOutline(g, x + OUTPUT_RENDER_X - 1, y + OUTPUT_RENDER_Y - 1);
        if (!output.isEmpty())
        {
            g.item(output, x + OUTPUT_RENDER_X, y + OUTPUT_RENDER_Y);
            g.itemDecorations(font, output, x + OUTPUT_RENDER_X, y + OUTPUT_RENDER_Y);
            if (over(mx, my, x + OUTPUT_RENDER_X, y + OUTPUT_RENDER_Y, 16, 16))
                hoveredTooltip = output.getDisplayName().getString() + Component.translatable("colonylink.term.pickup_pattern").getString();
        }
        else if (over(mx, my, x + OUTPUT_RENDER_X, y + OUTPUT_RENDER_Y, 16, 16))
            hoveredTooltip = Component.translatable("colonylink.term.encoded_appears").getString();

        // Encode button
        boolean canEncode = domumQueueSelected >= 0
                && domumQueueSelected < domumQueue.size()
                && !blank.isEmpty()
                && output.isEmpty();
        TerminalSkin.draw(g, encodePushed ? TerminalSkin.BTN_PUSH : TerminalSkin.BTN,
                x + ENCODE_BTN_X, y + ENCODE_BTN_Y);
        if (!canEncode)
            g.fill(x + ENCODE_BTN_X, y + ENCODE_BTN_Y,
                    x + ENCODE_BTN_X + ENCODE_BTN_W, y + ENCODE_BTN_Y + ENCODE_BTN_H, 0xAA111133);
        if (over(mx, my, x + ENCODE_BTN_X, y + ENCODE_BTN_Y, ENCODE_BTN_W, ENCODE_BTN_H))
        {
            boolean queueReady = domumQueueSelected >= 0 && domumQueueSelected < domumQueue.size();
            boolean slotReady  = !target.isEmpty();
            boolean blankReady = !blank.isEmpty();
            boolean outFree    = output.isEmpty();
            if (queueReady && blankReady && outFree)
                hoveredTooltip = Component.translatable("colonylink.term.encode_queue").getString();
            else if (slotReady && blankReady && outFree)
                hoveredTooltip = Component.translatable("colonylink.term.encode_ref").getString();
            else if (!blankReady)
                hoveredTooltip = Component.translatable("colonylink.term.encode_need_blank").getString();
            else if (!outFree)
                hoveredTooltip = Component.translatable("colonylink.term.encode_take_first").getString();
            else
                hoveredTooltip = Component.translatable("colonylink.term.encode_select_first").getString();
        }

        // Scrollable list (x33 y3 → x124 y66)
        renderDomumQueueList(g, ox, oy, mx, my);
    }

    /**
     * Renders the scrollable list of Domum items awaiting encoding.
     * Coordinates relative to the cutter panel origin (ox, oy).
     */
    private void renderDomumQueueList(GuiGraphicsExtractor g, int ox, int oy, int mx, int my)
    {
        g.fill(ox + CUT_LIST_X, oy + CUT_LIST_Y,
                ox + CUT_LIST_X + CUT_LIST_W, oy + CUT_LIST_Y + CUT_LIST_H, 0x88111111);

        // Clip so text does not spill out of the list area
        g.enableScissor(ox + CUT_LIST_X, oy + CUT_LIST_Y,
                ox + CUT_LIST_X + CUT_LIST_W, oy + CUT_LIST_Y + CUT_LIST_H);

        if (domumQueue.isEmpty())
        {
            GuiCompat.drawString(g, font, Component.translatable("colonylink.term.no_pending").getString(),
                    ox + CUT_LIST_X + 4, oy + CUT_LIST_Y + 4, 0xFFAAAAAA, false);
            GuiCompat.drawString(g, font, Component.translatable("colonylink.term.click_nopattern").getString(),
                    ox + CUT_LIST_X + 4, oy + CUT_LIST_Y + 14, 0xFFAAAAAA, false);
            GuiCompat.drawString(g, font, Component.translatable("colonylink.term.in_clipboard").getString(),
                    ox + CUT_LIST_X + 4, oy + CUT_LIST_Y + 24, 0xFFAAAAAA, false);
            g.disableScissor();
            return;
        }

        int maxScroll = Math.max(0, domumQueue.size() - CUT_ROWS_VIS);
        domumQueueScroll = Math.min(domumQueueScroll, maxScroll);

        for (int row = 0; row < CUT_ROWS_VIS; row++)
        {
            int idx = domumQueueScroll + row;
            if (idx >= domumQueue.size()) break;

            ItemStack stack = domumQueue.get(idx);
            int rx = ox + CUT_LIST_X;
            int ry = oy + CUT_LIST_Y + row * CUT_ROW_H;

            boolean selected = (idx == domumQueueSelected);
            boolean hovered  = over(mx, my, rx, ry, CUT_LIST_W, CUT_ROW_H);
            if (selected)
                g.fill(rx, ry, rx + CUT_LIST_W, ry + CUT_ROW_H, 0x881A3A6A);
            else if (hovered)
                g.fill(rx, ry, rx + CUT_LIST_W, ry + CUT_ROW_H, 0x44FFFFFF);

            g.fill(rx, ry + CUT_ROW_H - 1, rx + CUT_LIST_W, ry + CUT_ROW_H, 0x44AAAAAA);

            int itemY = ry + (CUT_ROW_H - 16) / 2;
            g.item(stack, rx + 2, itemY);

            String name = stack.getDisplayName().getString();
            int maxChars = (CUT_LIST_W - 22) / 5;
            if (name.length() > maxChars) name = name.substring(0, maxChars - 1) + "…";
            GuiCompat.drawString(g, font, name, rx + 20, ry + (CUT_ROW_H - 8) / 2, 0xFFDDDDDD, false);

            if (hovered)
            {
                StringBuilder tip = new StringBuilder();
                tip.append("§f").append(stack.getDisplayName().getString());

                // Variant (BLOCK_STATE)
                net.minecraft.world.item.component.BlockItemStateProperties bs =
                        stack.get(net.minecraft.core.component.DataComponents.BLOCK_STATE);
                if (bs != null && !bs.properties().isEmpty())
                    for (var e : bs.properties().entrySet())
                        tip.append("\n§8").append(e.getKey()).append(": §7").append(e.getValue());

                // Materials
                if (stack.getItem() instanceof net.minecraft.world.item.BlockItem bi
                        && bi.getBlock() instanceof com.ldtteam.domumornamentum.block.IMateriallyTexturedBlock tb)
                {
                    com.ldtteam.domumornamentum.client.model.data.MaterialTextureData td =
                            com.ldtteam.domumornamentum.client.model.data.MaterialTextureData.readFromItemStack(stack);
                    for (var comp : tb.getComponents())
                    {
                        net.minecraft.world.level.block.Block mat = td.components().get(comp.getId());
                        if (mat != null)
                            tip.append("\n§7• §f").append(new ItemStack(mat).getDisplayName().getString());
                    }
                }

                tip.append(Component.translatable("colonylink.term.click_select").getString());
                hoveredTooltip = tip.toString();
            }
        }

        g.disableScissor();

        // Scroll indicator outside the clip (so it isn't cut off)
        if (maxScroll > 0)
        {
            String scrollInfo = (domumQueueScroll + 1) + "-"
                    + Math.min(domumQueueScroll + CUT_ROWS_VIS, domumQueue.size())
                    + "/" + domumQueue.size();
            GuiCompat.drawString(g, font, scrollInfo,
                    ox + CUT_LIST_X + CUT_LIST_W - font.width(scrollInfo) - 2,
                    oy + CUT_LIST_Y + CUT_LIST_H - 8, 0xFF777777, false);
        }
    }

    // Read-only materials
    private void updateMaterialCache(ItemStack target)
    {
        if (ItemStack.isSameItemSameComponents(target, lastTargetForCache)) return;
        lastTargetForCache = target.copy();
        cachedMaterials.clear();
        if (!(target.getItem() instanceof BlockItem bi)) return;
        Block block = bi.getBlock();
        if (!(block instanceof IMateriallyTexturedBlock tb)) return;
        MaterialTextureData td = MaterialTextureData.readFromItemStack(target);
        for (IMateriallyTexturedBlockComponent comp : tb.getComponents())
        {
            Block mat = td.components().get(comp.getId());
            cachedMaterials.add(mat != null ? new ItemStack(mat, 1) : ItemStack.EMPTY);
        }
    }

    private void renderMaterials(GuiGraphicsExtractor g, int x, int y, int mx, int my)
    {
        int count = Math.min(cachedMaterials.size(), MAT_MAX);
        for (int i = 0; i < count; i++)
        {
            int ix = x + MAT_START_X + i * MAT_PITCH;
            int iy = y + MAT_Y;
            ItemStack mat = cachedMaterials.get(i);
            renderSlotOutline(g, ix - 1, iy - 1);
            if (mat.isEmpty())
            {
                g.fill(ix, iy, ix + 16, iy + 16, 0x88FF0000);
                if (over(mx, my, ix, iy, 16, 16))
                    hoveredTooltip = Component.translatable("colonylink.term.missing_material").getString();
            }
            else
            {
                g.item(mat, ix, iy);
                if (over(mx, my, ix, iy, 16, 16))
                    hoveredTooltip = Component.translatable("colonylink.term.material_required", mat.getDisplayName()).getString();
            }
        }
        if (cachedMaterials.size() > MAT_MAX)
            GuiCompat.drawString(g, font, "+" + (cachedMaterials.size() - MAT_MAX),
                    x + MAT_START_X + MAT_MAX * MAT_PITCH, y + MAT_Y + 4, 0xFF9999BB, false);
    }

    // ── Outline slot ──────────────────────────────────────────────────────────
    private void renderSlotOutline(GuiGraphicsExtractor g, int x, int y)
    {
        g.fill(x,      y,      x + 18, y + 1,  0xFF373737);
        g.fill(x,      y,      x + 1,  y + 18, 0xFF373737);
        g.fill(x,      y + 17, x + 18, y + 18, 0xFFFFFFFF);
        g.fill(x + 17, y,      x + 18, y + 18, 0xFFFFFFFF);
    }

    // ── Labels WH / AE au dessus des search bars ──────────────────────────────
    private void renderPanelLabels(GuiGraphicsExtractor g, int x, int y)
    {
        // WH label zone: x=9..133, y=2..14
        String wh = Component.translatable("colonylink.term.label_warehouse").getString();
        String whCnt = String.valueOf(whFiltered.size());
        GuiCompat.drawString(g, font, wh,    x + 9, y + 3, C_TEXT_WH, false);
        GuiCompat.drawString(g, font, whCnt, x + 9 + SEARCH_WH_W - font.width(whCnt) - 1, y + 3, C_TEXT_MUTED, false);

        // AE label zone: x=233..358, y=2..14
        String ae = Component.translatable("colonylink.term.label_applied").getString();
        String aeCnt = String.valueOf(meFiltered.size());
        GuiCompat.drawString(g, font, ae,    x + SEARCH_AE_X, y + 3, C_TEXT_AE, false);
        GuiCompat.drawString(g, font, aeCnt, x + SEARCH_AE_X + SEARCH_AE_W - font.width(aeCnt) - 1, y + 3, C_TEXT_MUTED, false);
    }

    // ── Search bar ────────────────────────────────────────────────────────────
    private void renderSearch(GuiGraphicsExtractor g, int x, int y, int w, int h,
                              String txt, boolean focused, int count, boolean isWh)
    {
        // The search bar background is already in the PNG; we only draw the text
        if (txt.isEmpty())
            GuiCompat.drawString(g, font, Component.translatable("colonylink.term.search").getString(), x + 2, y + 1, C_SF_PH, false);
        else
            GuiCompat.drawString(g, font, txt, x + 2, y + 1, C_SF_TEXT, false);
        if (focused && (System.currentTimeMillis() / 500) % 2 == 0)
            GuiCompat.drawString(g, font, "|", x + 2 + font.width(txt), y + 1, C_SF_TEXT, false);
    }

    // Vertical scrollbar
    // Track in the PNG: 10px wide (x=176..185 WH, x=401..410 AE)
    // Active center rail: 4px (x=179..182 WH, x=404..407 AE)
    // slider.png 13×16: black corners at x=0 and x=12; usable body x=1..11 = 11px
    // → blit srcX=1, w=11; dest trackX-1 to center the 11px thumb on the 10px track
    private static final int SCROLL_THUMB_H = 16;

    private void renderScrollbar(GuiGraphicsExtractor g, int trackX, int guiY,
                                 int totalItems, int scroll)
    {
        if (totalItems <= 0) return;
        int maxRows   = (int) Math.ceil(totalItems / (double) PANEL_COLS);
        int maxScroll = maxRows - visRows();
        if (maxScroll <= 0) return;

        int trackH = (SCROLL_BOT_Y - SCROLL_TOP_Y) - rowDY(); // visible track
        int thumbY = guiY + SCROLL_TOP_Y
                + (int) ((trackH - SCROLL_THUMB_H) * (float) scroll / maxScroll);

        // Usable PNG body = cols 1..11 = 11px
        // Shift 1px left (trackX-1) to center on the 10px track
        TerminalSkin.draw(g, TerminalSkin.THUMB, trackX - 1, thumbY);
    }

    // ── Slider WH/ME (horizontal, un seul checkbox centré) ───────────────────
    private void renderSlider(GuiGraphicsExtractor g, int x, int y, int mx, int my)
    {
        int sx = x + SLIDER_X;
        int sy = y + SLIDER_Y;
        // Single 22×12 checkbox centered in the 40×13 zone
        // WH first → checkbox_wh.png, AE first → checkbox_ae.png
        int offsetX = (SLIDER_W - 22) / 2;  // (40-22)/2 = 9 → centered
        TerminalSkin.draw(g, warehouseFirst ? TerminalSkin.CHK_WH : TerminalSkin.CHK_AE, sx + offsetX, sy);

        if (over(mx, my, sx, sy, SLIDER_W, SLIDER_H))
            hoveredTooltip = warehouseFirst
                    ? Component.translatable("colonylink.term.jei_wh").getString()
                    : Component.translatable("colonylink.term.jei_me").getString();
    }

    // ── Bouton push (centre colonne) ──────────────────────────────────────────
    private void renderPushButton(GuiGraphicsExtractor g, int mx, int my,
                                  int bx, int by, boolean pushed, String tooltip)
    {
        boolean hov = over(mx, my, bx, by, BTN_SZ, BTN_SZ);
        TerminalSkin.draw(g, pushed ? TerminalSkin.BTN_PUSH : TerminalSkin.BTN, bx, by);
        if (hov) hoveredTooltip = tooltip;
    }

    // ── Items des panels ──────────────────────────────────────────────────────
    private void renderPanelItems(GuiGraphicsExtractor g, int panelX, int guiY, boolean isWh, int mx, int my)
    {
        // Hovered slot computed like the tooltips (itemSlot) → highlight always
        // aligned with the tooltip at any GUI Scale.
        int hovIdx = itemSlot(mx, my, panelX);
        if (isWh && !hasWarehouseCard)
        {
            // "No card" overlay
            g.fill(panelX, guiY + Y_ITEMS,
                    panelX + PANEL_COLS * SLOT_PITCH,
                    guiY + Y_ITEMS + visRows() * SLOT_PITCH,
                    C_NOCARD_OV);
            String msg1 = Component.translatable("colonylink.term.nocard1").getString();
            String msg2 = Component.translatable("colonylink.term.nocard2").getString();
            int cx = panelX + (PANEL_COLS * SLOT_PITCH) / 2;
            int cy = guiY + Y_ITEMS + (visRows() * SLOT_PITCH) / 2;
            GuiCompat.drawCenteredString(g, font, msg1, cx, cy - 10, 0xFF8888BB);
            GuiCompat.drawCenteredString(g, font, msg2, cx, cy + 2, 0xFF8888BB);
            return;
        }
        if (isWh && !whErrorMsg.isEmpty())
        {
            g.fill(panelX, guiY + Y_ITEMS,
                    panelX + PANEL_COLS * SLOT_PITCH,
                    guiY + Y_ITEMS + visRows() * SLOT_PITCH,
                    C_NOCARD_OV);
            // 1.7.0-beta.2 — the server sends a translation key (card status); wrapped
            // to the panel width. A plain string is shown as-is (translatable falls back
            // to the key itself).
            java.util.List<net.minecraft.util.FormattedCharSequence> lines = font.split(
                    Component.translatable(whErrorMsg), PANEL_COLS * SLOT_PITCH - 8);
            int cx = panelX + (PANEL_COLS * SLOT_PITCH) / 2;
            int cy = guiY + Y_ITEMS + (visRows() * SLOT_PITCH) / 2 - lines.size() * 5;
            for (int i = 0; i < lines.size(); i++)
                GuiCompat.drawCenteredString(g, font, lines.get(i), cx, cy + i * 10, 0xFF8888BB);
            return;
        }
        if (!isWh && !ae2Active())
        {
            g.fill(panelX, guiY + Y_ITEMS,
                    panelX + PANEL_COLS * SLOT_PITCH,
                    guiY + Y_ITEMS + visRows() * SLOT_PITCH,
                    C_OFFLINE_OV);
            GuiCompat.drawCenteredString(g, font, Component.translatable("colonylink.term.ae2_offline").getString(),
                    panelX + (PANEL_COLS * SLOT_PITCH) / 2,
                    guiY + Y_ITEMS + (visRows() * SLOT_PITCH) / 2, 0xFFFF4444);
            return;
        }

        int scroll = isWh ? whScroll : meScroll;
        Set<Integer> sel = isWh ? whSelected : meSelected;
        List<?> list = isWh ? whFiltered : meFiltered;

        for (int r = 0; r < visRows(); r++)
        {
            for (int c = 0; c < PANEL_COLS; c++)
            {
                int idx = scroll * PANEL_COLS + r * PANEL_COLS + c;
                if (idx >= list.size()) return;

                int sx = panelX + c * SLOT_PITCH;
                int sy = guiY + Y_ITEMS + r * SLOT_PITCH;
                boolean hov = (hovIdx == r * PANEL_COLS + c);

                if (sel.contains(idx))      g.fill(sx, sy, sx + SLOT_SZ, sy + SLOT_SZ, C_SELECTED);
                else if (hov)               g.fill(sx, sy, sx + SLOT_SZ, sy + SLOT_SZ, C_HOVER_SL);

                ItemStack stack; long count; boolean craftable = false;
                if (isWh) { var e = whFiltered.get(idx); stack = e.stack(); count = e.count(); }
                else      { var e = meFiltered.get(idx); stack = e.stack(); count = e.count(); craftable = e.craftable(); }

                g.item(stack, sx, sy);
                g.itemDecorations(font, stack, sx, sy, "");

                if (!isWh)
                {
                    if (craftable && count <= 0)
                    {
                        g.fill(sx, sy, sx + SLOT_SZ, sy + SLOT_SZ, C_CRAFTONLY_OV);
                        GuiCompat.drawString(g, font, "+", sx + 4, sy + 4, 0xFF00CCFF, true);
                        continue;
                    }
                    if (craftable) g.fill(sx + 13, sy, sx + 16, sy + 3, C_CRAFTABLE_DOT);
                }

                if (count > 0) drawCount(g, sx, sy, count);
            }
        }
    }

    // ── Compteur items (style AE2) ────────────────────────────────────────────
    private void drawCount(GuiGraphicsExtractor g, int slotX, int slotY, long count)
    {
        String txt   = fmtCount(count);
        float  scale = COUNT_FONT_SCALE;
        int    wRaw  = font.width(txt);
        int    hRaw  = font.lineHeight;
        float  drawX = (slotX + 15) / scale - wRaw;
        float  drawY = (slotY + 15) / scale - hRaw;

        var pose = g.pose();
        pose.pushMatrix();
        pose.scale(scale, scale);
        GuiCompat.drawString(g, font, txt, (int) Math.round(drawX) + 1, (int) Math.round(drawY) + 1, C_COUNT_SHADOW, false);
        GuiCompat.drawString(g, font, txt, (int) Math.round(drawX),     (int) Math.round(drawY),     C_COUNT_TEXT,   false);
        pose.popMatrix();
    }

    // Panel hit-test (single source of truth)
    // v1.6.6 — one place resolves cursor→item for the WH/ME panels, in RAW SCREEN
    // COORDINATES (no scale transform: mx = rawMx in render()). Consumed by
    // renderTooltipForPanel, getPanelStackAt AND getPanelSlotArea (JEI integration).
    // WH first, then ME (the panels don't overlap). Same guards as display: warehouse
    // card present + no error for WH, AE2 active for ME.

    /**
     * Item under the cursor in a panel, with its display info and the SCREEN
     * geometry of the hovered slot ({@code slotX}/{@code slotY} = top-left corner
     * of the 16×16 icon). stack and area come from the same hit → never diverge.
     */
    private record PanelHit(boolean isWh, ItemStack stack, long count, boolean craftable,
                            int slotX, int slotY) {}

    /** Hit-test WH then ME in SCREEN coords. {@code null} if nothing under the cursor. */
    private PanelHit panelHitAt(int mx, int my)
    {
        // WH
        int panelX = leftPos + X_ITEMS_WH;
        int s = itemSlot(mx, my, panelX);
        if (s >= 0 && hasWarehouseCard && whErrorMsg.isEmpty())
        {
            int i = whScroll * PANEL_COLS + s;
            if (i < whFiltered.size())
            {
                var e = whFiltered.get(i);
                int sx = panelX + (s % PANEL_COLS) * SLOT_PITCH;
                int sy = topPos + Y_ITEMS + (s / PANEL_COLS) * SLOT_PITCH;
                return new PanelHit(true, e.stack(), e.count(), false, sx, sy);
            }
        }
        // ME
        panelX = leftPos + X_ITEMS_AE;
        s = itemSlot(mx, my, panelX);
        if (s >= 0 && ae2Active())
        {
            int i = meScroll * PANEL_COLS + s;
            if (i < meFiltered.size())
            {
                var e = meFiltered.get(i);
                int sx = panelX + (s % PANEL_COLS) * SLOT_PITCH;
                int sy = topPos + Y_ITEMS + (s / PANEL_COLS) * SLOT_PITCH;
                return new PanelHit(false, e.stack(), e.count(), e.craftable(), sx, sy);
            }
        }
        return null;
    }

    /**
     * Panel ItemStack (WH or ME) under the cursor, or {@link ItemStack#EMPTY}.
     * SCREEN coordinates (as received from Minecraft, no scale transform).
     * Public entry point for JEI integration (hover → show recipe/uses).
     */
    public ItemStack getPanelStackAt(double mouseX, double mouseY)
    {
        PanelHit hit = panelHitAt((int) mouseX, (int) mouseY);
        return hit != null ? hit.stack() : ItemStack.EMPTY;
    }

    /**
     * SCREEN area (16×16, {@code SLOT_SZ}) of the panel slot under the cursor, to
     * anchor the JEI highlight on the icon. {@code null} if no slot is hovered.
     * Delegates to the same {@link #panelHitAt} as {@link #getPanelStackAt} → stack
     * and area always consistent (one hit-test source).
     */
    public Rect2i getPanelSlotArea(double mouseX, double mouseY)
    {
        PanelHit hit = panelHitAt((int) mouseX, (int) mouseY);
        return hit != null ? new Rect2i(hit.slotX(), hit.slotY(), SLOT_SZ, SLOT_SZ) : null;
    }

    // ── Tooltips items ────────────────────────────────────────────────────────
    private void renderTooltipForPanel(GuiGraphicsExtractor g, int mx, int my, int rawMx, int rawMy)
    {
        PanelHit hit = panelHitAt(mx, my);
        if (hit == null) return;

        if (hit.isWh())
        {
            g.setComponentTooltipForNextFrame(font, List.of(
                            hit.stack().getDisplayName(),
                            Component.translatable("colonylink.term.in_warehouse", hit.count()),
                            Component.translatable("colonylink.term.controls_wh")),
                    rawMx, rawMy);
        }
        else
        {
            List<Component> lines = new ArrayList<>();
            lines.add(hit.stack().getDisplayName());
            if (hit.craftable() && hit.count() <= 0)
                lines.add(Component.translatable("colonylink.term.craftable_only"));
            else if (hit.craftable())
                lines.add(Component.translatable("colonylink.term.in_me_craftable", hit.count()));
            else
                lines.add(Component.translatable("colonylink.term.in_me", hit.count()));
            lines.add(Component.translatable("colonylink.term.controls_me"));
            g.setComponentTooltipForNextFrame(font, lines, rawMx, rawMy);
        }
    }

    // INPUT
    // =========================================================================
    @Override public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick)
    {
        final double mx = event.x(), my = event.y(); final int btn = event.button(); // PORT(26.1): input events


        boolean shift = this.minecraft.hasShiftDown();
        int yb = topPos - rowDY();   // base des elements ancres en bas

        // v1.4.2 — Crafting tab click
        if (over(mx, my, leftPos + TAB_X, yb + TAB_CRAFT_Y, TAB_W, TAB_H))
        {
            activeTab = 0;
            menu.setTab(0);
            return true;
        }

        // v1.4.2 — Cutter tab click
        if (over(mx, my, leftPos + TAB_X, yb + TAB_CUTTER_Y, TAB_W, TAB_H))
        {
            activeTab = 1;
            menu.setTab(1);
            return true;
        }

        // v1.4.8 — Cutter: Encode button from the queue
        if (activeTab == 1 && over(mx, my, leftPos + ENCODE_BTN_X, yb + ENCODE_BTN_Y,
                ENCODE_BTN_W, ENCODE_BTN_H))
        {
            ItemStack blank = menu.getSlot(BLANK_PATTERN_SLOT).getItem();
            boolean outputFree = menu.getDomumOutput().isEmpty();

            // Path 1: selection in the queue
            if (domumQueueSelected >= 0 && domumQueueSelected < domumQueue.size()
                    && !blank.isEmpty() && outputFree)
            {
                encodePushed = true;
                ItemStack selectedStack = domumQueue.get(domumQueueSelected);
                net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                        new DomumEncodePatternPacket(menu.getHostPos(), menu.getHostSide(), selectedStack));
            }
            // Path 2: legacy path via domumTargetSlot (manually placed item)
            else if (menu.canEncode())
            {
                encodePushed = true;
                menu.sendEncodeRequest();
            }
            return true;
        }

        // v1.4.8 — Cutter: queue list click
        if (activeTab == 1)
        {
            int ox = leftPos + PG_X;
            int oy = topPos - rowDY() + PG_Y;

            // Click in the list area
            if (over(mx, my, ox + CUT_LIST_X, oy + CUT_LIST_Y, CUT_LIST_W, CUT_LIST_H))
            {
                int relY = (int) my - (oy + CUT_LIST_Y);
                int row  = relY / CUT_ROW_H;
                int idx  = domumQueueScroll + row;
                if (idx >= 0 && idx < domumQueue.size())
                {
                    domumQueueSelected = (domumQueueSelected == idx) ? -1 : idx; // toggle
                    // Material preview from the selected item
                    if (domumQueueSelected >= 0)
                        updateMaterialCache(domumQueue.get(domumQueueSelected));
                }
                return true;
            }

            // Blank Pattern slot
            if (over(mx, my, leftPos + BLANK_RENDER_X, yb + BLANK_RENDER_Y, 16, 16))
            {
                this.slotClicked(menu.getSlot(BLANK_PATTERN_SLOT), BLANK_PATTERN_SLOT, btn,
                        net.minecraft.world.inventory.ContainerInput.PICKUP);
                return true;
            }

            // Output slot — retrieve the encoded pattern
            if (over(mx, my, leftPos + OUTPUT_RENDER_X, yb + OUTPUT_RENDER_Y, 16, 16))
            {
                if (!menu.getDomumOutput().isEmpty())
                {
                    this.slotClicked(menu.getSlot(DOMUM_OUTPUT_SLOT), DOMUM_OUTPUT_SLOT, btn,
                            net.minecraft.world.inventory.ContainerInput.PICKUP);
                    // La ligne disparaît via tickDomumQueueCraftableCheck au prochain scan AE2
                }
                return true;
            }

            // Reference item slot (for the material preview)
            if (over(mx, my, ox + CUT_REF_X, oy + CUT_REF_Y, 16, 16))
            {
                this.slotClicked(menu.getSlot(DOMUM_TARGET_SLOT), DOMUM_TARGET_SLOT, btn,
                        net.minecraft.world.inventory.ContainerInput.PICKUP);
                return true;
            }
        }

        // ── Shift+clic inventaire → WH ou ME ────────────────────────────────
        if (shift && !this.minecraft.hasControlDown()
                && mx >= leftPos + INV_X && mx < leftPos + INV_X + 9 * SLOT_PITCH)
        {
            boolean canSendToWh = hasWarehouseCard && whErrorMsg.isEmpty();
            boolean canSendToMe = ae2Active();
            boolean canSend     = warehouseFirst ? canSendToWh : canSendToMe;

            if (canSend)
            {
                int invSlotIdx = -1;
                if (my >= yb + INV_Y && my < yb + INV_Y + 3 * SLOT_PITCH)
                {
                    int r = ((int) my - yb - INV_Y) / SLOT_PITCH;
                    int c = ((int) mx - leftPos - INV_X) / SLOT_PITCH;
                    invSlotIdx = PLAYER_INV_START + r * 9 + c;
                }
                else if (my >= yb + HOTBAR_Y && my < yb + HOTBAR_Y + SLOT_PITCH)
                {
                    int c = ((int) mx - leftPos - INV_X) / SLOT_PITCH;
                    invSlotIdx = PLAYER_HOTBAR_START + c;
                }
                if (invSlotIdx >= 0 && invSlotIdx < menu.slots.size())
                {
                    ItemStack invStack = menu.slots.get(invSlotIdx).getItem();
                    if (!invStack.isEmpty())
                    {
                        TerminalTransferPacket.Direction dir = warehouseFirst
                                ? TerminalTransferPacket.Direction.INV_TO_WH
                                : TerminalTransferPacket.Direction.INV_TO_ME;
                        ClientPacketDistributor.sendToServer(new TerminalTransferPacket(
                                invStack.copy(), invStack.getCount(), dir, partHostPos(), partSide()));
                        menu.slots.get(invSlotIdx).set(ItemStack.EMPTY);
                        return true;
                    }
                }
            }
            if (my >= yb + INV_Y && my < yb + HOTBAR_Y + SLOT_PITCH) return true;
        }

        // Scrollbar drag WH
        // Hitbox aligned with the rendered thumb: trackX-1, width=11
        if (over(mx, my, leftPos + SCROLL_WH_X - 1, topPos + SCROLL_TOP_Y,
                11, (SCROLL_BOT_Y - SCROLL_TOP_Y) - rowDY()))
        {
            draggingWh = true; draggingAe = false;
            dragStartY = (int) my;
            dragStartScroll = whScroll;
            return true;
        }

        // Scrollbar drag AE
        if (over(mx, my, leftPos + SCROLL_AE_X - 1, topPos + SCROLL_TOP_Y,
                11, (SCROLL_BOT_Y - SCROLL_TOP_Y) - rowDY()))
        {
            draggingAe = true; draggingWh = false;
            dragStartY = (int) my;
            dragStartScroll = meScroll;
            return true;
        }

        // Search bars
        if (my >= topPos + SEARCH_Y && my < topPos + SEARCH_Y + SEARCH_H)
        {
            if (mx >= leftPos + SEARCH_WH_X && mx < leftPos + SEARCH_WH_X + SEARCH_WH_W)
            { whSearchFocused = true; meSearchFocused = false; return true; }
            if (mx >= leftPos + SEARCH_AE_X && mx < leftPos + SEARCH_AE_X + SEARCH_AE_W)
            { meSearchFocused = true; whSearchFocused = false; return true; }
        }
        whSearchFocused = meSearchFocused = false;

        // WH/ME slider
        if (over(mx, my, leftPos + SLIDER_X, yb + SLIDER_Y, SLIDER_W, SLIDER_H))
        {
            warehouseFirst = !warehouseFirst;
            WarehouseLinkTerminalMenu.warehouseFirst = warehouseFirst;
            return true;
        }

        // Button B1: transfer selection WH↔ME
        if (over(mx, my, leftPos + BTN1_X, topPos + BTN1_Y, BTN_SZ, BTN_SZ))
        {
            btn1Pushed = true;
            if (!whSelected.isEmpty())
            {
                for (int i : new ArrayList<>(whSelected))
                    if (i < whFiltered.size())
                    { var e = whFiltered.get(i); sendTx(e.stack(), (int) Math.min(e.count(), e.stack().getMaxStackSize()), TerminalTransferPacket.Direction.WH_TO_ME); }
                whSelected.clear();
            }
            else if (!meSelected.isEmpty())
            {
                for (int i : new ArrayList<>(meSelected))
                    if (i < meFiltered.size())
                    { var e = meFiltered.get(i); sendTx(e.stack(), (int) Math.min(e.count(), e.stack().getMaxStackSize()), TerminalTransferPacket.Direction.ME_TO_WH); }
                meSelected.clear();
            }
            return true;
        }

        // Button B2: transfer selection → inventory
        if (over(mx, my, leftPos + BTN2_X, topPos + BTN2_Y, BTN_SZ, BTN_SZ))
        {
            btn2Pushed = true;
            if (!whSelected.isEmpty())
            {
                for (int i : new ArrayList<>(whSelected))
                    if (i < whFiltered.size())
                    { var e = whFiltered.get(i); sendTx(e.stack(), (int) Math.min(e.count(), e.stack().getMaxStackSize()), TerminalTransferPacket.Direction.WH_TO_PLAYER); }
                whSelected.clear();
            }
            else if (!meSelected.isEmpty())
            {
                for (int i : new ArrayList<>(meSelected))
                    if (i < meFiltered.size())
                    { var e = meFiltered.get(i); sendTx(e.stack(), (int) Math.min(e.count(), e.stack().getMaxStackSize()), TerminalTransferPacket.Direction.ME_TO_PLAYER); }
                meSelected.clear();
            }
            return true;
        }

        // Button 7: clear grid → inventory (invisible hitbox on PNG background)
        if (over(mx, my, leftPos + 136, yb + 273, 7, 7))
        {
            clearGridToInventory(shift);
            return true;
        }

        // Button 8: result → WH (invisible hitbox on PNG background)
        // count=1 → 1 craft; count>1 (shift) → max loop server-side
        if (over(mx, my, leftPos + 252, yb + 279, 7, 7))
        {
            ItemStack r = menu.getCraftResult().getItem(0);
            if (!r.isEmpty())
                sendTx(r.copy(), shift ? r.getMaxStackSize() : 1,
                        TerminalTransferPacket.Direction.RESULT_TO_WH);
            return true;
        }

        // Button 9: result → ME (invisible hitbox on PNG background)
        if (over(mx, my, leftPos + 268, yb + 279, 8, 7))
        {
            ItemStack r = menu.getCraftResult().getItem(0);
            if (!r.isEmpty())
                sendTx(r.copy(), shift ? r.getMaxStackSize() : 1,
                        TerminalTransferPacket.Direction.RESULT_TO_ME);
            return true;
        }

        // Button 10: result → inventory (invisible hitbox on PNG background)
        if (over(mx, my, leftPos + 260, yb + 313, 7, 7))
        {
            ItemStack r = menu.getCraftResult().getItem(0);
            if (!r.isEmpty())
                this.slotClicked(menu.getSlot(CRAFT_OUTPUT_SLOT), CRAFT_OUTPUT_SLOT, 0,
                        net.minecraft.world.inventory.ContainerInput.QUICK_MOVE);
            return true;
        }

        // Panel WH — pick / set-down / select
        int ws = itemSlot((int) mx, (int) my, leftPos + X_ITEMS_WH);
        if (ws >= 0 && hasWarehouseCard && whErrorMsg.isEmpty())
        {
            int idx = whScroll * PANEL_COLS + ws;
            ItemStack carried = minecraft.player.containerMenu.getCarried();
            if (!shift && !carried.isEmpty())
            {
                sendTx(carried.copy(), carried.getCount(), TerminalTransferPacket.Direction.PUT_INTO_WH);
                whSelected.clear(); meSelected.clear();
                return true;
            }
            if (idx < whFiltered.size())
            {
                var e = whFiltered.get(idx);
                if (shift)
                {
                    // Simple shift+click → transfer 1 stack to inventory
                    // (if a drag starts, dragStartSlotWh tells them apart)
                    dragStartSlotWh = idx;
                    dragStartSlotAe = -1;
                    // Don't transfer yet — wait for mouseReleased to tell a click
                    // (1 slot) from a drag (2+ slots)
                    meSelected.clear();
                }
                else
                {
                    int pickCount = (btn == 1)
                            ? (int) Math.min(Math.max(1, (e.count() + 1) / 2), e.stack().getMaxStackSize())
                            : (int) Math.min(e.count(), e.stack().getMaxStackSize());
                    sendPickup(e.stack(), pickCount, TerminalTransferPacket.Direction.PICKUP_FROM_WH);
                    whSelected.clear(); meSelected.clear();
                }
            }
            return true;
        }

        // Panel AE — pick / set-down / select / autocraft
        int ms = itemSlot((int) mx, (int) my, leftPos + X_ITEMS_AE);
        if (ms >= 0 && ae2Active())
        {
            int idx = meScroll * PANEL_COLS + ms;
            ItemStack carried = minecraft.player.containerMenu.getCarried();
            if (!shift && btn != 2 && !carried.isEmpty())
            {
                sendTx(carried.copy(), carried.getCount(), TerminalTransferPacket.Direction.PUT_INTO_ME);
                whSelected.clear(); meSelected.clear();
                return true;
            }
            if (idx < meFiltered.size())
            {
                var e = meFiltered.get(idx);
                if (btn == 2)
                {
                    if (e.craftable())
                        ClientPacketDistributor.sendToServer(new TerminalCraftPacket(
                                TerminalCraftPacket.Mode.AUTOCRAFT, e.stack().copyWithCount(1), 1,
                                partHostPos(), partSide()));
                    return true;
                }
                if (shift)
                {
                    // Simple shift+click → transfer 1 stack to inventory
                    // Wait for mouseReleased to tell a click (1 slot) from a drag (2+ slots)
                    dragStartSlotAe = idx;
                    dragStartSlotWh = -1;
                    whSelected.clear();
                }
                else
                {
                    int pickCount;
                    if (btn == 1)
                    {
                        long half = (e.count() + 1) / 2;
                        pickCount = (int) Math.min(Math.max(1, half), e.stack().getMaxStackSize());
                        if (e.count() <= 0) pickCount = 0;
                    }
                    else
                    {
                        pickCount = e.count() > 0
                                ? (int) Math.min(e.count(), e.stack().getMaxStackSize()) : 1;
                    }
                    if (pickCount > 0)
                        sendPickup(e.stack(), pickCount, TerminalTransferPacket.Direction.PICKUP_FROM_ME);
                    whSelected.clear(); meSelected.clear();
                }
            }
            return true;
        }

        return super.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(mx, my, event.buttonInfo()), doubleClick);
    }

    @Override public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event)
    {
        final double mx = event.x(), my = event.y(); final int btn = event.button(); // PORT(26.1): input events


        draggingWh = draggingAe = false;
        btn1Pushed = btn2Pushed = false;
        encodePushed = false;

        // Simple shift+click (no drag to another slot) → transfer 1 stack → inventory
        if (dragStartSlotWh >= 0 && dragStartSlotWh < whFiltered.size())
        {
            var e = whFiltered.get(dragStartSlotWh);
            int count = (int) Math.min(e.count(), e.stack().getMaxStackSize());
            if (count > 0)
                sendTx(e.stack().copy(), count, TerminalTransferPacket.Direction.WH_TO_PLAYER);
        }
        dragStartSlotWh = -1;

        if (dragStartSlotAe >= 0 && dragStartSlotAe < meFiltered.size())
        {
            var e = meFiltered.get(dragStartSlotAe);
            int count = e.count() > 0 ? (int) Math.min(e.count(), e.stack().getMaxStackSize()) : 0;
            if (count > 0)
                sendTx(e.stack().copy(), count, TerminalTransferPacket.Direction.ME_TO_PLAYER);
        }
        dragStartSlotAe = -1;

        return super.mouseReleased(new net.minecraft.client.input.MouseButtonEvent(mx, my, event.buttonInfo()));
    }

    @Override public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy)
    {
        final double mx = event.x(), my = event.y(); final int btn = event.button(); // PORT(26.1): input events


        // Scrollbar drag
        if (draggingWh || draggingAe)
        {
            int trackH    = (SCROLL_BOT_Y - SCROLL_TOP_Y) - rowDY(); // visible track
            int deltaPx   = (int) my - dragStartY;
            List<?> list  = draggingWh ? whFiltered : meFiltered;
            int maxRows   = (int) Math.ceil(list.size() / (double) PANEL_COLS);
            int maxScroll = Math.max(1, maxRows - visRows());
            int pixPerRow = Math.max(1, (trackH - SCROLL_THUMB_H) / maxScroll);
            int newScroll = dragStartScroll + deltaPx / pixPerRow;
            if (draggingWh) whScroll = clamp(newScroll, whFiltered.size());
            else             meScroll = clamp(newScroll, meFiltered.size());
            return true;
        }

        // ── Shift+drag sélection items (commence à 2 slots différents) ──────────
        if (this.minecraft.hasShiftDown())
        {
            int ws = itemSlot((int) mx, (int) my, leftPos + X_ITEMS_WH);
            if (ws >= 0 && hasWarehouseCard)
            {
                int idx = whScroll * PANEL_COLS + ws;
                if (idx < whFiltered.size() && idx != dragStartSlotWh)
                {
                    // On a slot different from the start → selection mode activated
                    if (dragStartSlotWh >= 0) whSelected.add(dragStartSlotWh); // add the start slot
                    whSelected.add(idx);
                    meSelected.clear();
                    dragStartSlotWh = -1; // cancels the transfer on release
                    return true;
                }
            }
            int ms = itemSlot((int) mx, (int) my, leftPos + X_ITEMS_AE);
            if (ms >= 0 && ae2Active())
            {
                int idx = meScroll * PANEL_COLS + ms;
                if (idx < meFiltered.size() && idx != dragStartSlotAe)
                {
                    if (dragStartSlotAe >= 0) meSelected.add(dragStartSlotAe);
                    meSelected.add(idx);
                    whSelected.clear();
                    dragStartSlotAe = -1;
                    return true;
                }
            }
        }
        return super.mouseDragged(new net.minecraft.client.input.MouseButtonEvent(mx, my, event.buttonInfo()), dx, dy);
    }

    @Override public boolean mouseScrolled(double mx, double my, double dx, double dy)
    {

        // Scroll Domum list (Cutter tab active)
        if (activeTab == 1)
        {
            int ox = leftPos + PG_X;
            int oy = topPos - rowDY() + PG_Y;
            if (mx >= ox + CUT_LIST_X && mx < ox + CUT_LIST_X + CUT_LIST_W
                    && my >= oy + CUT_LIST_Y && my < oy + CUT_LIST_Y + CUT_LIST_H)
            {
                int maxScroll = Math.max(0, domumQueue.size() - CUT_ROWS_VIS);
                domumQueueScroll = Math.max(0, Math.min(domumQueueScroll - (int) Math.signum(dy), maxScroll));
                return true;
            }
        }

        int rT = topPos + Y_ITEMS;
        int rB = rT + visRows() * SLOT_PITCH;

        // Scroll WH: mouse over the WH panel only (not the scrollbar track)
        if (my >= rT && my < rB
                && mx >= leftPos + X_ITEMS_WH
                && mx < leftPos + X_ITEMS_WH + PANEL_COLS * SLOT_PITCH)
        { whScroll = clamp(whScroll - (int) Math.signum(dy), whFiltered.size()); return true; }

        // Scroll AE: mouse over the AE panel only (not the scrollbar track)
        if (my >= rT && my < rB
                && mx >= leftPos + X_ITEMS_AE
                && mx < leftPos + X_ITEMS_AE + PANEL_COLS * SLOT_PITCH)
        { meScroll = clamp(meScroll - (int) Math.signum(dy), meFiltered.size()); return true; }

        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override public boolean keyPressed(net.minecraft.client.input.KeyEvent event)
    {
        final int key = event.key(), scan = event.scancode(), mod = event.modifiers(); // PORT(26.1): input events

        if (whSearchFocused)
        {
            if (key == 259 && !whSearch.isEmpty()) whSearch = whSearch.substring(0, whSearch.length() - 1);
            else if (key == 256) whSearchFocused = false;
            rebuildWh(); return true;
        }
        if (meSearchFocused)
        {
            if (key == 259 && !meSearch.isEmpty()) meSearch = meSearch.substring(0, meSearch.length() - 1);
            else if (key == 256) meSearchFocused = false;
            rebuildMe(); return true;
        }
        return super.keyPressed(event);
    }

    @Override public boolean charTyped(net.minecraft.client.input.CharacterEvent event)
    {
        final char c = (char) event.codepoint(); final int mod = 0; // PORT(26.1): input events

        if (whSearchFocused) { whSearch += c; rebuildWh(); return true; }
        if (meSearchFocused) { meSearch += c; rebuildMe(); return true; }
        return super.charTyped(event);
    }

    // CRAFT BUTTON ACTIONS
    private void clearGridToInventory(boolean shift)
    {
        ClientPacketDistributor.sendToServer(new TerminalTransferPacket(
                ItemStack.EMPTY, 0,
                TerminalTransferPacket.Direction.CRAFT_TO_PLAYER,
                partHostPos(), partSide()));
        if (shift)
        {
            ItemStack r = menu.getCraftResult().getItem(0);
            if (!r.isEmpty())
                this.slotClicked(menu.getSlot(CRAFT_OUTPUT_SLOT), CRAFT_OUTPUT_SLOT, 0,
                        net.minecraft.world.inventory.ContainerInput.QUICK_MOVE);
        }
    }

    // HELPERS
    private void sendPickup(ItemStack template, int count, TerminalTransferPacket.Direction dir)
    { ClientPacketDistributor.sendToServer(new TerminalTransferPacket(template.copyWithCount(1), count, dir, partHostPos(), partSide())); }

    private void sendTx(ItemStack s, int n, TerminalTransferPacket.Direction d)
    { ClientPacketDistributor.sendToServer(new TerminalTransferPacket(s.copyWithCount(n), n, d, partHostPos(), partSide())); }

    /** Returns the linear index (row*PANEL_COLS+col) of the hovered slot in a panel, or -1. */
    private int itemSlot(int mx, int my, int panelX)
    {
        int rT = topPos + Y_ITEMS;
        int rB = rT + visRows() * SLOT_PITCH;
        if (mx < panelX || mx >= panelX + PANEL_COLS * SLOT_PITCH || my < rT || my >= rB) return -1;
        int col = (mx - panelX) / SLOT_PITCH;
        int row = (my - rT)     / SLOT_PITCH;
        if (col >= PANEL_COLS || row >= visRows()) return -1;
        return row * PANEL_COLS + col;
    }

    private int clamp(int s, int tot)
    {
        int max = Math.max(0, (int) Math.ceil(tot / (double) PANEL_COLS) - visRows());
        return Math.max(0, Math.min(s, max));
    }

    private boolean over(double mx, double my, int bx, int by, int bw, int bh)
    { return mx >= bx && mx < bx + bw && my >= by && my < by + bh; }

    private static String fmtCount(long n)
    {
        if (n < 0)               return "0";
        if (n < 1_000)           return Long.toString(n);
        if (n < 10_000)          return fmtDecimal(n, 1_000)       + "K";
        if (n < 1_000_000)       return (n / 1_000)                + "K";
        if (n < 10_000_000)      return fmtDecimal(n, 1_000_000)   + "M";
        if (n < 1_000_000_000)   return (n / 1_000_000)            + "M";
        if (n < 10_000_000_000L) return fmtDecimal(n, 1_000_000_000L) + "B";
        if (n < 1_000_000_000_000L) return (n / 1_000_000_000L)   + "B";
        return "\u221e";
    }

    private static String fmtDecimal(long n, long divisor)
    { return (n / divisor) + "." + ((n * 10 / divisor) % 10); }

    // 1.7.0-beta.5 (audit #10) — the three helpers below are the ONLY places this screen
    // touches menu.getPart(); all tolerate a null Part (see init()).

    private net.minecraft.core.BlockPos partHostPos()
    {
        var part = menu.getPart();
        if (part == null) return net.minecraft.core.BlockPos.ZERO;
        var be = part.getHostBlockEntity();
        return be != null ? be.getBlockPos() : net.minecraft.core.BlockPos.ZERO;
    }

    private int partSide()
    {
        var part = menu.getPart();
        if (part == null) return 0;
        var s = part.getSide();
        return s != null ? s.ordinal() : 0;
    }

    private boolean ae2Active()
    {
        var part = menu.getPart();
        return part != null && part.isAe2Active();
    }

    public record MeItemEntry(ItemStack stack, long count, boolean craftable) {}
}
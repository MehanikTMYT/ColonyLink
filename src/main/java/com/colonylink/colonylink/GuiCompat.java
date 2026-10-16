package com.colonylink.colonylink;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * PORT(26.1): text drawing helpers for the GUI extraction API.
 *
 * <p>Since 1.21.6 text colours are taken as ARGB verbatim, so a plain {@code 0xRRGGBB} colour (alpha 0) draws
 * invisible. 1.21.1's font treated a zero alpha as opaque; {@link #textColor} keeps that behaviour so the existing
 * colour constants keep working.
 */
public final class GuiCompat
{
    private GuiCompat() {}

    public static int textColor(final int color)
    {
        return (color & 0xFC000000) == 0 ? color | 0xFF000000 : color;
    }

    public static void drawString(final GuiGraphicsExtractor g, final Font font, final String text, final int x, final int y, final int color)
    {
        g.text(font, text, x, y, textColor(color));
    }

    public static void drawString(final GuiGraphicsExtractor g, final Font font, final String text, final int x, final int y, final int color, final boolean shadow)
    {
        g.text(font, text, x, y, textColor(color), shadow);
    }

    public static void drawString(final GuiGraphicsExtractor g, final Font font, final Component text, final int x, final int y, final int color)
    {
        g.text(font, text, x, y, textColor(color));
    }

    public static void drawString(final GuiGraphicsExtractor g, final Font font, final Component text, final int x, final int y, final int color, final boolean shadow)
    {
        g.text(font, text, x, y, textColor(color), shadow);
    }

    public static void drawString(final GuiGraphicsExtractor g, final Font font, final FormattedCharSequence text, final int x, final int y, final int color)
    {
        g.text(font, text, x, y, textColor(color));
    }

    public static void drawString(final GuiGraphicsExtractor g, final Font font, final FormattedCharSequence text, final int x, final int y, final int color, final boolean shadow)
    {
        g.text(font, text, x, y, textColor(color), shadow);
    }

    public static void drawCenteredString(final GuiGraphicsExtractor g, final Font font, final String text, final int x, final int y, final int color)
    {
        g.centeredText(font, text, x, y, textColor(color));
    }

    public static void drawCenteredString(final GuiGraphicsExtractor g, final Font font, final Component text, final int x, final int y, final int color)
    {
        g.centeredText(font, text, x, y, textColor(color));
    }

    public static void drawCenteredString(final GuiGraphicsExtractor g, final Font font, final FormattedCharSequence text, final int x, final int y, final int color)
    {
        g.centeredText(font, text, x, y, textColor(color));
    }
}

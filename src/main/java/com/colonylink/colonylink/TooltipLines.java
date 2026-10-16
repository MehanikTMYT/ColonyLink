package com.colonylink.colonylink;

import net.minecraft.network.chat.Component;

import java.util.AbstractList;
import java.util.function.Consumer;

/**
 * PORT(26.1): {@code Item#appendHoverText} hands out a {@code Consumer<Component>} instead of a mutable list.
 * This append-only list forwards every added line to it, so the existing tooltip code keeps its {@code add} calls.
 */
final class TooltipLines extends AbstractList<Component>
{
    private final Consumer<Component> sink;
    private int size;

    TooltipLines(final Consumer<Component> sink)
    {
        this.sink = sink;
    }

    @Override
    public void add(final int index, final Component line)
    {
        sink.accept(line);
        size++;
    }

    @Override
    public Component get(final int index)
    {
        throw new UnsupportedOperationException("tooltip lines are write-only");
    }

    @Override
    public int size()
    {
        return size;
    }
}

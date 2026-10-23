package com.colonylink.colonylink;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 1.7.0-beta.2 — what a Warehouse Link Card is linked to, stored as the
 * {@code colonylink:card_link} data component.
 *
 * MineColonies reuses colony IDs after a deletion (ColonyList hands out freed
 * indices first), so (colonyId, dimension) alone is not an identity: {@code center}
 * is the fingerprint that tells the original colony apart from a later one that
 * got the same ID. {@code colonyName} is a copy for the tooltip (the client has no
 * colony view outside the colony). No permission is ever stored here: rights are
 * checked live, on the acting player, at every action.
 */
public record CardLink(int colonyId, ResourceKey<Level> dimension, BlockPos center,
                       String colonyName, BlockPos warehousePos)
{
    public static final Codec<CardLink> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("colony_id").forGetter(CardLink::colonyId),
            ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(CardLink::dimension),
            BlockPos.CODEC.fieldOf("center").forGetter(CardLink::center),
            Codec.STRING.fieldOf("colony_name").forGetter(CardLink::colonyName),
            BlockPos.CODEC.fieldOf("warehouse_pos").forGetter(CardLink::warehousePos)
    ).apply(i, CardLink::new));

    public static final StreamCodec<ByteBuf, CardLink> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, CardLink::colonyId,
            ResourceKey.streamCodec(Registries.DIMENSION), CardLink::dimension,
            BlockPos.STREAM_CODEC, CardLink::center,
            ByteBufCodecs.STRING_UTF8, CardLink::colonyName,
            BlockPos.STREAM_CODEC, CardLink::warehousePos,
            CardLink::new);
}

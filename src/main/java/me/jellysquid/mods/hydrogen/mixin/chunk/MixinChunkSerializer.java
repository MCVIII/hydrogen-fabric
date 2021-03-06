package me.jellysquid.mods.hydrogen.mixin.chunk;

import net.minecraft.block.Block;
import net.minecraft.fluid.Fluid;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.ChunkSerializer;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.UpgradeData;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.poi.PointOfInterestStorage;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.class_6752; // net/minecraft/world/chunk/BlendingData
import net.minecraft.class_6755; // net/minecraft/world/tick/WorldTickScheduler

import java.util.function.Consumer;

@Mixin(ChunkSerializer.class)
public abstract class MixinChunkSerializer {
    private static final ThreadLocal<NbtCompound> CAPTURED_TAGS = new ThreadLocal<>();

    @Shadow
    private static void loadEntities(ServerWorld world, NbtCompound nbt, WorldChunk chunk) {
        throw new UnsupportedOperationException();
    }

    @Inject(method = "deserialize", at = @At("HEAD"))
    private static void captureTag(ServerWorld world, PointOfInterestStorage poiStorage, ChunkPos pos, NbtCompound tag, CallbackInfoReturnable<ProtoChunk> cir) {
        // We can't access the method parameters in the later redirect, so capture them for this thread
        CAPTURED_TAGS.set(tag);
    }

    @Redirect(method = "deserialize", at = @At(value = "NEW", target = "net/minecraft/world/chunk/WorldChunk"))
    private static WorldChunk create(World world, ChunkPos pos, UpgradeData upgradeData,
                                     class_6755<Block> blockTickScheduler, class_6755<Fluid> fluidTickScheduler,
                                     long inhabitedTime, @Nullable ChunkSection[] sections,
                                     @Nullable Consumer<WorldChunk> loadToWorldConsumer, class_6752 blendingData) {
        NbtCompound rootTag = CAPTURED_TAGS.get();

        if (rootTag == null) {
            throw new IllegalStateException("No captured tag was found");
        }

        NbtCompound level = rootTag.getCompound("Level");

        // The (misleadingly named) writeEntities function below only cares about these two tags
        // However, the lambda can end up staying loaded with the chunk if it isn't within ticking radius of a player yet
        // In order to prevent huge NBT blobs from remaining loaded in memory all the time, we can strip all the other
        // data to save a fair bit of memory.
        NbtCompound strippedTag = new NbtCompound();
        strippedTag.put("Entities", level.getList("Entities", 10));
        strippedTag.put("TileEntities", level.getList("TileEntities", 10));

        return new WorldChunk(world, pos, upgradeData, blockTickScheduler, fluidTickScheduler, inhabitedTime, sections, (chunk) -> {
            loadEntities((ServerWorld) world, strippedTag, chunk);
        }, blendingData);
    }

    @Inject(method = "deserialize", at = @At("RETURN"))
    private static void releaseTag(ServerWorld world, PointOfInterestStorage poiStorage, ChunkPos pos, NbtCompound tag, CallbackInfoReturnable<ProtoChunk> cir) {
        // Avoid leaking tags in memory
        CAPTURED_TAGS.remove();
    }
}

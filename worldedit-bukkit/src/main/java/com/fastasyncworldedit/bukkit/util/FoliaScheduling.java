package com.fastasyncworldedit.bukkit.util;

import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Helpers for running work on the Folia thread that owns a region or entity.
 * <p>
 * Every blocking call runs inline when the current thread already owns the target (waiting on a task queued to the
 * current region would deadlock that region), completes exceptionally when the task throws (instead of leaving the
 * caller blocked forever), and gives up after {@link #TIMEOUT_SECONDS} so a region that never runs the task (plugin
 * disabling, world unloading, region stalled) cannot hang an edit thread indefinitely.
 * <p>
 * Only call these on Folia ({@link com.fastasyncworldedit.core.util.FoliaUtil#isFoliaServer()}).
 */
public final class FoliaScheduling {

    /**
     * Maximum time to wait for a task scheduled on another region or entity.
     */
    public static final long TIMEOUT_SECONDS = 60;

    private FoliaScheduling() {
    }

    /**
     * Run the supplier on the thread owning the chunk at the given block coordinates and wait for the result.
     */
    public static <T> T callAt(World world, int blockX, int blockZ, Supplier<T> supplier) {
        final int chunkX = blockX >> 4;
        final int chunkZ = blockZ >> 4;
        if (Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ)) {
            return supplier.get();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getServer().getRegionScheduler().execute(
                WorldEditPlugin.getInstance(),
                world,
                chunkX,
                chunkZ,
                () -> complete(future, supplier)
        );
        return await(future, "region " + world.getName() + " [" + chunkX + ", " + chunkZ + "]");
    }

    /**
     * Run the supplier on the thread owning the given location and wait for the result.
     */
    public static <T> T callAt(Location location, Supplier<T> supplier) {
        return callAt(location.getWorld(), location.getBlockX(), location.getBlockZ(), supplier);
    }

    /**
     * Run the task on the thread owning the given chunk without waiting. Runs inline if already owned.
     */
    public static void runAtChunk(World world, int chunkX, int chunkZ, Runnable task) {
        if (Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ)) {
            task.run();
            return;
        }
        Bukkit.getServer().getRegionScheduler().execute(WorldEditPlugin.getInstance(), world, chunkX, chunkZ, task);
    }

    /**
     * Run the supplier on the thread owning the entity and wait for the result. The entity scheduler follows the entity
     * across regions. Returns {@code retiredValue} if the entity was removed before the task could run.
     */
    public static <T> T callOnEntity(Entity entity, Supplier<T> supplier, T retiredValue) {
        if (Bukkit.isOwnedByCurrentRegion(entity)) {
            return supplier.get();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        boolean scheduled = entity.getScheduler().execute(
                WorldEditPlugin.getInstance(),
                () -> complete(future, supplier),
                () -> future.complete(retiredValue),
                1L
        );
        if (!scheduled) {
            return retiredValue;
        }
        return await(future, "entity " + entity.getUniqueId());
    }

    private static <T> void complete(CompletableFuture<T> future, Supplier<T> supplier) {
        if (future.isDone()) {
            // The caller timed out and gave up: don't run stale work.
            return;
        }
        try {
            future.complete(supplier.get());
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
    }

    private static <T> T await(CompletableFuture<T> future, String target) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Folia task on " + target, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Folia task on " + target + " failed", cause);
        } catch (TimeoutException e) {
            future.cancel(false);
            throw new IllegalStateException(
                    "Timed out after " + TIMEOUT_SECONDS + "s waiting for Folia task on " + target, e);
        }
    }

}

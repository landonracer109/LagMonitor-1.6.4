package techit.lagmonitor;

/**
 * Works out what a server-thread stack sample was doing. Minecraft 1.6.4 runs with SRG method
 * names, so the markers below are SRG names:
 *
 *   func_70316_g  TileEntity.updateEntity   (a block entity ticking: turtles, machines, pipes, ...)
 *   func_70071_h_ Entity.onUpdate           (mobs, items, players, minecarts, ...)
 *   func_72955_a  WorldServer.tickUpdates   (scheduled block updates: redstone, fluids, crops, ...)
 *   func_73156_b  ChunkProviderServer.unloadQueuedChunks (unloading and saving chunks)
 *   func_73158_c  ChunkProviderServer.loadChunk          (loading or generating chunks)
 *   func_71747_b  NetworkListenThread.networkTick       (handling players' packets)
 *   func_72835_b  WorldServer.tick          (the rest of the world tick: weather, random block ticks, ...)
 *   func_71267_a  MinecraftServer.saveAllWorlds, func_73041_k WorldServer.flush,
 *   DimensionManager.unloadWorlds, ThreadedFileIOBase     (saving worlds, waiting for the disk)
 */
final class Classify {
    static final String BLOCK_ENTITY = "block entities";
    static final String ENTITY = "entities";
    static final String HANDLER = "mod tick handlers";
    static final String BLOCK_UPDATES = "scheduled block updates";
    static final String CHUNK_UNLOAD = "chunk unloading and saving";
    static final String CHUNK_LOAD = "chunk loading and generation";
    static final String WORLD_SAVE = "world saving (autosave, dimension unloading)";
    static final String NETWORK = "player packets";
    static final String WORLD = "world tick (other)";
    static final String OTHER = "other";

    /** Result for one sample: a category, and for some categories the class responsible. */
    static final class Result {
        String category;
        String detail;
    }

    private Classify() {
    }

    static void classify(StackTraceElement[] st, Result out) {
        out.category = OTHER;
        out.detail = null;
        // Walk from the outermost frame (end of the array) inwards, so the first match is what the
        // tick was doing at the top level, then look for the innermost culprit where useful.
        for (int i = st.length - 1; i >= 0; i--) {
            String m = st[i].getMethodName();
            if ("func_70316_g".equals(m)) {
                out.category = BLOCK_ENTITY;
                out.detail = st[i].getClassName();
                return;
            }
            if ("func_70071_h_".equals(m)) {
                out.category = ENTITY;
                out.detail = st[i].getClassName();
                return;
            }
            if (("tickStart".equals(m) || "tickEnd".equals(m)) && !st[i].getClassName().startsWith("cpw.mods.fml.")
                && !st[i].getClassName().startsWith("techit.lagmonitor.")) {
                out.category = HANDLER;
                out.detail = st[i].getClassName();
                return;
            }
        }
        // Otherwise the innermost recognised part of the tick wins (chunk loading caused by a block
        // update counts as chunk loading).
        for (int i = 0; i < st.length; i++) {
            String m = st[i].getMethodName();
            String c = st[i].getClassName();
            if ("func_71267_a".equals(m) || "func_73041_k".equals(m) || "unloadWorlds".equals(m)
                || c.endsWith("ThreadedFileIOBase")) {
                out.category = WORLD_SAVE;
                return;
            }
            if ("func_73158_c".equals(m) || "func_73154_d".equals(m)) {
                out.category = CHUNK_LOAD;
                return;
            }
            if ("func_73156_b".equals(m)) {
                out.category = CHUNK_UNLOAD;
                return;
            }
            if ("func_72955_a".equals(m)) {
                out.category = BLOCK_UPDATES;
                return;
            }
            if ("func_71747_b".equals(m)) {
                out.category = NETWORK;
                return;
            }
            if ("func_72835_b".equals(m)) {
                out.category = WORLD;
                return;
            }
        }
    }

    /** The first three parts of a class's package, e.g. "dan200.computercraft.shared". */
    static String packageOf(String className) {
        int dots = 0;
        for (int i = 0; i < className.length(); i++) {
            if (className.charAt(i) == '.' && ++dots == 3) {
                return className.substring(0, i);
            }
        }
        int last = className.lastIndexOf('.');
        return last > 0 ? className.substring(0, last) : className;
    }
}

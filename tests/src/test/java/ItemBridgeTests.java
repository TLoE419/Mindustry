import static org.junit.jupiter.api.Assertions.*;
import static mindustry.Vars.*;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import arc.*;
import arc.ApplicationCore;
import arc.Core;
import arc.backend.headless.HeadlessApplication;
import arc.files.Fi;
import arc.util.Log;
import arc.util.Time;
import arc.util.io.*;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.core.GameState.State;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.maps.Map;
import mindustry.mod.Mod;
import mindustry.mod.Mods.LoadedMod;
import mindustry.net.Net;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.distribution.ItemBridge;
import mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild;


// ItemBridge Test Cases

public class ItemBridgeTests
{

    static Map testMap;
    static boolean initialized;
    static final Fi testDataFolder = new Fi("../../tests/build/test_data");

    // Common setup for all tests: launch a headless application and load the test map.
    @BeforeAll
    public static void launchApplication()
    {
        launchApplication(true);
    }

    public static void launchApplication(boolean clear) 
    {
        if (initialized) return;
        initialized = true;

        try 
        {
            boolean[] begins = {false};
            Throwable[] exceptionThrown = {null};
            Log.useColors = false;

            ApplicationCore core = new ApplicationCore() 
            {
                @Override
                public void setup() 
                {
                    if (clear) 
                    {
                        testDataFolder.deleteDirectory();
                    }
                    Core.settings.setDataDirectory(testDataFolder);
                    headless = true;
                    net = new Net(null);
                    tree = new FileTree();
                    Vars.init();
                    world = new World() 
                    {
                        @Override
                        public float getDarkness(int x, int y) 
                        {
                            return 0;
                        }
                    };
                    content.createBaseContent();
                    mods.loadScripts();
                    content.createModContent();

                    add(logic = new Logic());
                    add(netServer = new NetServer());

                    content.init();
                    mods.eachClass(Mod::init);

                    if (mods.hasContentErrors()) 
                    {
                        for (LoadedMod mod : mods.list())
                        {
                            if (mod.hasContentErrors())
                            {
                                for (Content cont : mod.erroredContent) 
                                {
                                    throw new RuntimeException(
                                        "error in file: " + cont.minfo.sourceFile.path(),
                                        cont.minfo.baseError);
                                }
                            }
                        }
                    }
                }

                @Override
                public void init() 
                {
                    super.init();
                    begins[0] = true;
                    testMap = maps.loadInternalMap("serpulo/groundZero");
                    Thread.currentThread().interrupt();
                }
            };

            new HeadlessApplication(core, throwable -> exceptionThrown[0] = throwable);

            while (!begins[0])
            {
                if (exceptionThrown[0] != null) fail(exceptionThrown[0]);
                Thread.sleep(10);
            }
        } 
        catch (Throwable r) 
        {
            fail(r);
        }
    }

    @BeforeEach
    void resetWorld()
    {
        Time.setDeltaProvider(() -> 1f);
        logic.reset();
        state.set(State.playing);
    }

    // Advance game by a number of frames, setting efficiency = 1 and calling updateTile.
    void updateBuildings(int times)
    {
        for (int i = 0; i < times; i++)
        {
            Time.update();
            for (Tile tile : world.tiles)
            {
                if (tile.build != null)
                {
                    tile.build.efficiency = 1f;
                    tile.build.potentialEfficiency = 1f;
                    tile.build.optionalEfficiency = 1f;
                }
            }
            for (Tile tile : world.tiles) 
            {
                if (tile.build != null && tile.isCenter())
                {
                    tile.build.updateTile();
                }
            }
        }
    }

    // Place phaseConveyor at a tile
    ItemBridgeBuild placeBridge(int x, int y)
    {
        Tile tile = world.tile(x, y);
        tile.setBlock(Blocks.phaseConveyor, Team.sharded);
        ItemBridgeBuild build = (ItemBridgeBuild) tile.build;
        build.efficiency = 1f;
        build.potentialEfficiency = 1f;
        return build;
    }

    // linkValid / positionsValid — geometry checks

    @Test
    @DisplayName("TC-LV1: linkValid returns false when other tile is null")
    void testLinkValidNullOther() 
    {
        world.loadMap(testMap);
        ItemBridgeBuild src = placeBridge(10, 10);
        assertFalse(
            ((ItemBridge) Blocks.phaseConveyor).linkValid(src.tile, null),
            "linkValid must return false"
        );
    }

    @Test
    @DisplayName("TC-2: positionsValid returns true for same row within range")
    void testPositionsValidSameRowInRange()
    {
        world.loadMap(testMap);
        ItemBridge bridge = (ItemBridge) Blocks.phaseConveyor; // range = 12
        assertTrue(
            bridge.positionsValid(10, 10, 18, 10),
            "Horizontal distance 8 should be within range 12"
        );
    }

    @Test
    @DisplayName("TC-LV3: positionsValid returns false for diagonal positions")
    void testPositionsValidDiagonal()
    {
        world.loadMap(testMap);
        ItemBridge bridge = (ItemBridge) Blocks.phaseConveyor;
        assertFalse(
            bridge.positionsValid(10, 10, 12, 12),
            "Diagonal positions should be invalid for ItemBridge"
        );
    }

    @Test
    @DisplayName("TC-LV4: positionsValid returns false when distance exceeds range")
    void testPositionsValidOutOfRange() 
    {
        world.loadMap(testMap);
        ItemBridge bridge = (ItemBridge) Blocks.phaseConveyor; // range = 12
        assertFalse(
            bridge.positionsValid(10, 10, 30, 10),
            "Horizontal distance 20 should exceed range 12"
        );
    }

    @Test
    @DisplayName("TC-LV5: linkValid returns true for valid same-block same-team link")
    void testLinkValidSuccess() 
    {
        world.loadMap(testMap);
        ItemBridgeBuild src = placeBridge(10, 10);
        ItemBridgeBuild dst = placeBridge(16, 10);  // distance 6, within range 12

        // Point dst at src so checkDouble passes (dst.link != src.pos)
        dst.link = -1;

        assertTrue(
            ((ItemBridge) Blocks.phaseConveyor).linkValid(src.tile, dst.tile),
            "Two phase-conveyors on the same row within range should have a valid link"
        );
    }

    // updateTile — no valid link → doDump path (warmup stays 0)


    @Test
    @DisplayName("TC-UT1: updateTile with no link keep warmup at 0")
    void testUpdateTileNoLink()
    {
        world.loadMap(testMap);
        ItemBridgeBuild src = placeBridge(10, 10);
        // link = -1 by default → linkValid returns false

        updateBuildings(5);

        assertEquals(0f, src.warmup, 0.01f,
            "warmup should stay 0"
        );
    }

    @Test
    @DisplayName("TC-UT2: updateTile with valid link increase warmup toward 1")
    void testUpdateTileValidLinkWarmsUp()
    {
        world.loadMap(testMap);
        ItemBridgeBuild src = placeBridge(10, 10);
        ItemBridgeBuild dst = placeBridge(16, 10);

        src.link = dst.pos();

        updateBuildings(60);

        assertTrue(src.warmup > 0f,
            "warmup should increase above 0"
        );
    }

    @Test
    @DisplayName("TC-UT3: updateTile registers source in destination incoming list")
    void testUpdateTileRegistersIncoming()
    {
        world.loadMap(testMap);
        ItemBridgeBuild src = placeBridge(10, 10);
        ItemBridgeBuild dst = placeBridge(16, 10);

        src.link = dst.pos();

        updateBuildings(1);

        assertTrue(dst.incoming.contains(src.pos()),
            "Source bridge position should be added to destination's incoming list after updateTile"
        );
    }

    @Test
    @DisplayName("TC-UT4: updateTile does not duplicate incoming entries")
    void testUpdateTileNoDuplicateIncoming()
    {
        world.loadMap(testMap);
        ItemBridgeBuild src = placeBridge(10, 10);
        ItemBridgeBuild dst = placeBridge(16, 10);

        src.link = dst.pos();

        updateBuildings(10);  // run many frames

        int count = 0;
        for (int i = 0; i < dst.incoming.size; i++) 
        {
            if (dst.incoming.items[i] == src.pos()) count++;
        }
        assertEquals(1, count,
            "Source position should appear exactly once in destination incoming list"
        );
    }

    // updateTransport — item moves from src to dst

    @Test
    @DisplayName("TC-TR1: Item transfers from source to destination after transport time")
    void testItemTransfersToDest()
    {
        world.loadMap(testMap);
        ItemBridgeBuild src = placeBridge(10, 10);
        ItemBridgeBuild dst = placeBridge(16, 10);

        src.link = dst.pos();
        src.items.add(Items.copper, 1);

        updateBuildings(10);

        // Item should have moved from src to dst
        assertTrue(
            dst.items.get(Items.copper) > 0 || src.items.get(Items.copper) == 0,
            "Copper should have transferred from source to destination bridge"
        );
    }

    @Test
    @DisplayName("TC-TR2: Item stays at source when destination is full")
    void testItemStaysWhenDestFull() 
    {
        world.loadMap(testMap);
        ItemBridgeBuild src = placeBridge(10, 10);
        ItemBridgeBuild dst = placeBridge(16, 10);

        src.link = dst.pos();
        src.items.add(Items.copper, 1);

        // Fill the destination completely
        dst.items.add(Items.copper, dst.block.itemCapacity);

        updateBuildings(10);

        // Item should still be at source (acceptItem returns false when full)
        assertEquals(1, src.items.get(Items.copper),
            "Copper should remain at source when destination is at capacity"
        );
    }

    @Test
    @DisplayName("TC-TR3: moved flag becomes true after successful item transfer")
    void testMovedFlagAfterTransfer() 
    {
        world.loadMap(testMap);
        ItemBridgeBuild src = placeBridge(10, 10);
        ItemBridgeBuild dst = placeBridge(16, 10);

        src.link = dst.pos();
        src.items.add(Items.copper, 5);

        updateBuildings(5);

        assertTrue(src.moved,
            "moved flag should be true after a successful item transfer"
        );
    }

    // checkIncoming — stale entries are removed

    @Test
    @DisplayName("TC-CI1: checkIncoming removes stale entry when source link is broken")
    void testCheckIncomingRemovesStale()
    {
        world.loadMap(testMap);
        ItemBridgeBuild src = placeBridge(10, 10);
        ItemBridgeBuild dst = placeBridge(16, 10);

        src.link = dst.pos();
        updateBuildings(1);

        assertTrue(dst.incoming.contains(src.pos()), "Precondition: src should be in dst.incoming");

        src.link = -1;

        updateBuildings(1);

        assertFalse(dst.incoming.contains(src.pos()),
            "Stale incoming entry should be removed after source link is broken"
        );
    }

    // acceptItem / checkAccept — directional filtering

    @Test
    @DisplayName("TC-AI1: acceptItem returns false when bridge has no space")
    void testAcceptItemNoSpace() 
    {
        world.loadMap(testMap);
        ItemBridgeBuild src = placeBridge(10, 10);
        ItemBridgeBuild dst = placeBridge(16, 10);

        src.link = dst.pos();
        // Fill dst completely
        dst.items.add(Items.copper, dst.block.itemCapacity);

        assertFalse(dst.acceptItem(src, Items.copper),
            "acceptItem should return false when destination is at full capacity"
        );
    }

    @Test
    @DisplayName("TC-AI2: acceptItem returns false for wrong team")
    void testAcceptItemWrongTeam()
    {
        world.loadMap(testMap);
        ItemBridgeBuild src = placeBridge(10, 10);

        Tile dstTile = world.tile(16, 10);
        dstTile.setBlock(Blocks.phaseConveyor, Team.crux);  // enemy team
        ItemBridgeBuild dst = (ItemBridgeBuild) dstTile.build;
        dst.efficiency = 1f;

        src.link = dst.pos();

        assertFalse(dst.acceptItem(src, Items.copper),
            "acceptItem should return false when source is from a different team"
        );
    }

    @Test
    @DisplayName("TC-AI3: acceptItem returns true when linked source sends to dst")
    void testAcceptItemFromLinkedSource()
    {
        world.loadMap(testMap);
        ItemBridgeBuild src = placeBridge(10, 10);
        ItemBridgeBuild dst = placeBridge(16, 10);

        src.link = dst.pos();

        assertTrue(dst.acceptItem(src, Items.copper),
            "acceptItem should return true when receiving from a properly linked source"
        );
    }

    // shouldConsume — depends on valid link

    @Test
    @DisplayName("TC-SC1: shouldConsume returns false with no link")
    void testShouldConsumeNoLink()
    {
        world.loadMap(testMap);
        ItemBridgeBuild src = placeBridge(10, 10);

        assertFalse(src.shouldConsume(),
            "shouldConsume should return false when link is -1"
        );
    }

    @Test
    @DisplayName("TC-SC2: shouldConsume returns true with valid link and enabled")
    void testShouldConsumeValidLink() 
    {
        world.loadMap(testMap);
        ItemBridgeBuild src = placeBridge(10, 10);
        ItemBridgeBuild dst = placeBridge(16, 10);

        src.link = dst.pos();
        src.enabled = true;

        assertTrue(src.shouldConsume(),
            "shouldConsume should return true when a valid link exists and building is enabled"
        );
    }

    // 7. write / read — serialization round-trip

    @Test
    @DisplayName("TC-WR1: write and read correctly restore link and warmup")
    void testWriteReadRestoresState()
    {
        world.loadMap(testMap);
        ItemBridgeBuild src = placeBridge(10, 10);
        ItemBridgeBuild dst = placeBridge(16, 10);

        src.link = dst.pos();
        src.link = dst.pos();
        updateBuildings(30);

        float savedWarmup = src.warmup;
        int savedLink = src.link;

        // Write state
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(512);
        buf.position(0);
        src.write(new Writes(new ByteBufferOutput(buf)));

        // Reset state
        src.link = -1;
        src.warmup = 0f;
        src.incoming.clear();

        // Read state back
        buf.position(0);
        src.read(new Reads(new ByteBufferInput(buf)), (byte) 1);

        assertEquals(savedLink, src.link,
            "Serialized link value should be restored after read"
        );
        assertEquals(savedWarmup, src.warmup, 0.01f,
            "Serialized warmup value should be restored after read"
        );
    }

    @Test
    @DisplayName("TC-WR2: write and read restore incoming list entries")
    void testWriteReadRestoresIncoming()
    {
        world.loadMap(testMap);
        ItemBridgeBuild src = placeBridge(10, 10);
        ItemBridgeBuild dst = placeBridge(16, 10);

        src.link = dst.pos();
        updateBuildings(1);

        assertTrue(dst.incoming.contains(src.pos()), "Precondition: src in dst.incoming");

        int savedPos = src.pos();

        // Write dst state
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(512);
        buf.position(0);
        dst.write(new Writes(new ByteBufferOutput(buf)));

        // Clear dst incoming
        dst.incoming.clear();

        // Read back
        buf.position(0);
        dst.read(new Reads(new ByteBufferInput(buf)), (byte) 1);

        assertTrue(dst.incoming.contains(savedPos),
            "Incoming list should be restored after write/read round-trip"
        );
    }

    // canDump / checkDump — prevents back-feed into source direction

    @Test
    @DisplayName("TC-CD1: canDump returns true when no valid link exists (dump all directions)")
    void testCanDumpNoLink() 
    {
        world.loadMap(testMap);
        ItemBridgeBuild src = placeBridge(10, 10);

        Tile adjTile = world.tile(11, 10);
        adjTile.setBlock(Blocks.conveyor, Team.sharded);
        Building adj = adjTile.build;

        assertTrue(src.canDump(adj, Items.copper),
            "canDump should return true when there is no valid link (free to dump anywhere)"
        );
    }

    @Test
    @DisplayName("TC-CD2: canDump returns false when destination is in the same direction as the link")
    void testCanDumpBlockedByLink()
    {
        world.loadMap(testMap);
        ItemBridgeBuild src = placeBridge(10, 10);
        ItemBridgeBuild dst = placeBridge(16, 10);
        src.link = dst.pos();

        Tile adjTile = world.tile(11, 10);
        adjTile.setBlock(Blocks.conveyor, Team.sharded);
        Building adj = adjTile.build;

        assertFalse(src.canDump(adj, Items.copper),
            "canDump should return false when the dump target is in the same direction as the link"
        );
    }

    @Test
    @DisplayName("TC-CD3: canDump returns true when destination is perpendicular to the link direction")
    void testCanDumpPerpendicularToLink()
    {
        world.loadMap(testMap);
        ItemBridgeBuild src = placeBridge(10, 10);
        ItemBridgeBuild dst = placeBridge(16, 10);
        src.link = dst.pos();

        Tile adjTile = world.tile(10, 11);
        adjTile.setBlock(Blocks.conveyor, Team.sharded);
        Building adj = adjTile.build;

        assertTrue(src.canDump(adj, Items.copper),
            "canDump should return true when the dump target is perpendicular to the link direction"
        );
    }

    @Test
    @DisplayName("TC-CA1: checkAccept returns false when source is not linked and no valid link exists")
    void testCheckAcceptNoLink() 
    {
        world.loadMap(testMap);
        ItemBridgeBuild src = placeBridge(10, 10);
        ItemBridgeBuild dst = placeBridge(16, 10);

        assertFalse(dst.acceptItem(src, Items.copper),
            "acceptItem should return false when destination has no valid link and source is not a linked bridge"
        );
    }

    @Test
    @DisplayName("TC-LK1: linked() returns true when source bridge points back to this bridge")
    void testLinkedReturnsTrue() 
    {
        world.loadMap(testMap);
        ItemBridgeBuild src = placeBridge(10, 10);
        ItemBridgeBuild dst = placeBridge(16, 10);

        src.link = dst.pos();

        assertTrue(dst.acceptItem(src, Items.copper),
            "acceptItem should return true when the source is a properly linked bridge pointing at this building"
        );
    }
}

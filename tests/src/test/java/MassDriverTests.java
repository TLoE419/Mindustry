import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
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
import mindustry.Vars;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.core.GameState.State;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.gen.Building;
import mindustry.gen.*;
import mindustry.maps.Map;
import mindustry.mod.Mod;
import mindustry.mod.Mods.LoadedMod;
import mindustry.net.Net;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.distribution.MassDriver;
import mindustry.world.blocks.distribution.MassDriver.DriverState;



/**
 * MassDriver Finite State Machine Test Cases
 *
 * Tests cover all 6 FSM conditions (A, B, C, D, E, F) and state transitions
 */

public class MassDriverTests 
{
    static Map testMap;
    static boolean initialized;
    static final Fi testDataFolder = new Fi("../../tests/build/test_data");

    // Initializes the Mindustry application in headless mode before all tests.
    @BeforeAll
    public static void launchApplication()
    {
        launchApplication(true);
    }

    public static void launchApplication(boolean clear)
    {
        if(initialized) return;
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
                    //clear older data
                    if(clear)
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
                            //for world borders
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

                    if(mods.hasContentErrors())
                    {
                        for(LoadedMod mod : mods.list())
                        {
                            if(mod.hasContentErrors())
                            {
                                for(Content cont : mod.erroredContent)
                                {
                                    throw new RuntimeException("error in file: " + cont.minfo.sourceFile.path(), cont.minfo.baseError);
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

            while(!begins[0])
            {
                if(exceptionThrown[0] != null)
                {
                    fail(exceptionThrown[0]);
                }
                Thread.sleep(10);
            }

            Block block = Vars.content.getByName(ContentType.block, "build2");
            assertEquals("build2", block == null ? null : block.name, "2x2 construct block doesn't exist?");
        }
        catch(Throwable r)
        {
            fail(r);
        }
    }

    // Resets the game state before each test to ensure isolation and consistency.
    @BeforeEach
    void resetWorld()
    {
        Time.setDeltaProvider(() -> 1f);
        logic.reset();
        state.set(State.playing);
    }

    // Helper method to advance the game by a specified number of frames, updating all buildings each frame.
    void updateBuildings(int times)
    {
        for(int i = 0; i < times; i++)
        {
            Time.update();
            // Set efficiency before and after to ensure it's available
            for(Tile tile : world.tiles)
            {
                if(tile.build != null)
                {
                    tile.build.efficiency = 1f;  // Ensure power is available
                    tile.build.potentialEfficiency = 1f;
                    tile.build.optionalEfficiency = 1f;
                }
            }
            // Update only center tiles
            for(Tile tile : world.tiles)
            {
                if(tile.build != null && tile.isCenter())
                {
                    tile.build.updateTile();
                }
            }
        }
    }

    @Test
    @DisplayName("TC-Initial: Starts in IDLE state")
    void testInitialStateIdle() 
    {
        world.loadMap(testMap);

        // Place a Mass Driver to access its build instance
        Tile driverTile = world.tile(10, 10);
        driverTile.setBlock(Blocks.massDriver, Team.sharded);

        // Access the MassDriverBuild instance
        MassDriver.MassDriverBuild driver = (MassDriver.MassDriverBuild) driverTile.build;

        // public DriverState state = DriverState.idle;
        assertEquals("idle", driver.state.name(), "Initial state should be IDLE");
    }

    @Test
    @DisplayName("TC-CONDITION_A: Stay IDLE when shooter exists BUT no space")
    void testConditionAFailNoSpace() 
    {
        world.loadMap(testMap);

        // Setup two drivers
        Tile driver1Tile = world.tile(10, 10);
        Tile driver2Tile = world.tile(15, 10);
        driver1Tile.setBlock(Blocks.massDriver, Team.sharded);
        driver2Tile.setBlock(Blocks.massDriver, Team.sharded);

        // Access build instances
        MassDriver.MassDriverBuild driver1 = (MassDriver.MassDriverBuild) driver1Tile.build;
        MassDriver.MassDriverBuild driver2 = (MassDriver.MassDriverBuild) driver2Tile.build;

        // Add shooter to queue
        driver1.waitingShooters.add(driver2);

        // Fill driver1's storage completely
        for(Item item : content.items())
        {
            driver1.items.set(item, driver1.block.itemCapacity);
        }

        // if(!waitingShooters.isEmpty() && itemCapacity - items.total() >= minDistribute)
        assertEquals("idle", driver1.state.name(), "Start in IDLE");

        updateBuildings(1);

        assertEquals("idle", driver1.state.name(),
            "Should stay IDLE when no space available");
    }

    @Test
    @DisplayName("TC-CONDITION_B1: IDLE → SHOOTING when link exists (even with insufficient items)")
    void testConditionBFailNoItems()
    {
        world.loadMap(testMap);

        // Setup two drivers
        Tile driver1Tile = world.tile(10, 10);
        Tile driver2Tile = world.tile(50, 10);
        driver1Tile.setBlock(Blocks.massDriver, Team.sharded);
        driver2Tile.setBlock(Blocks.massDriver, Team.sharded);

        // Access build instances
        MassDriver.MassDriverBuild driver1 = (MassDriver.MassDriverBuild) driver1Tile.build;
        MassDriver.MassDriverBuild driver2 = (MassDriver.MassDriverBuild) driver2Tile.build;

        // Start in IDLE with no shooters
        assertEquals("idle", driver1.state.name(), "Start in IDLE");

        // Create valid link (Condition B satisfied)
        driver1.link = driver2.pos();
        // Note: No items added, items.total() = 0

        updateBuildings(1);

        // State should transition to SHOOTING
        assertEquals("shooting", driver1.state.name(),
            "Should transition to SHOOTING when link exists (Condition B takes priority)");
    }

    @Test
    @DisplayName("TC-CONDITION_B2: IDLE → ACCEPTING when shooter exists AND space available")
    void testConditionATransition()
    {
        world.loadMap(testMap);

        // Setup two drivers
        Tile driver1Tile = world.tile(10, 10);
        Tile driver2Tile = world.tile(15, 10);
        driver1Tile.setBlock(Blocks.massDriver, Team.sharded);
        driver2Tile.setBlock(Blocks.massDriver, Team.sharded);

        // Access build instances
        MassDriver.MassDriverBuild driver1 = (MassDriver.MassDriverBuild) driver1Tile.build;
        MassDriver.MassDriverBuild driver2 = (MassDriver.MassDriverBuild) driver2Tile.build;

        // Setup reverse link from driver2 to driver1 (required for valid shooter)
        driver2.link = driver1.pos();
        driver1.waitingShooters.add(driver2);

        // Ensure space available (items.total() = 0, capacity > 0)
        assertEquals("idle", driver1.state.name(), "Start in IDLE");
        assertEquals(0, driver1.items.total(), "Initially no items");

        updateBuildings(1);

        assertEquals("accepting", driver1.state.name(),
            "Should transition to ACCEPTING when waitingShooters not empty AND space available");
    }

    @Test
    @DisplayName("TC-CONDITION_C: IDLE → SHOOTING when link exists AND items available")
    void testConditionBTransition()
    {
        world.loadMap(testMap);

        // Setup two drivers
        Tile driver1Tile = world.tile(10, 10);
        Tile driver2Tile = world.tile(50, 10);
        driver1Tile.setBlock(Blocks.massDriver, Team.sharded);
        driver2Tile.setBlock(Blocks.massDriver, Team.sharded);

        // Access build instances
        MassDriver.MassDriverBuild driver1 = (MassDriver.MassDriverBuild) driver1Tile.build;
        MassDriver.MassDriverBuild driver2 = (MassDriver.MassDriverBuild) driver2Tile.build;

        // Create valid link
        driver1.link = driver2.pos();

        // Add items
        driver1.items.set(Items.copper, 15);

        // Line 153: else if(hasLink)
        // Line 154: state = DriverState.shooting;
        assertEquals("idle", driver1.state.name(), "Start in IDLE");

        updateBuildings(1);

        assertEquals("shooting", driver1.state.name(),
            "Should transition to SHOOTING when hasLink is true");
    }

    @Test
    @DisplayName("TC-CONDITION_D1: ACCEPTING → IDLE when shooter disappears")
    void testConditionCShooterGone() 
    {
        world.loadMap(testMap);

        // Setup two drivers
        Tile driver1Tile = world.tile(10, 10);
        Tile driver2Tile = world.tile(15, 10);
        driver1Tile.setBlock(Blocks.massDriver, Team.sharded);
        driver2Tile.setBlock(Blocks.massDriver, Team.sharded);

        // Access build instances
        MassDriver.MassDriverBuild driver1 = (MassDriver.MassDriverBuild) driver1Tile.build;
        MassDriver.MassDriverBuild driver2 = (MassDriver.MassDriverBuild) driver2Tile.build;

        // Enter ACCEPTING state
        driver1.waitingShooters.add(driver2);
        driver1.state = MassDriver.DriverState.accepting;

        updateBuildings(1);
        // Remove shooter
        driver1.waitingShooters.clear();

        updateBuildings(1);

        // if(currentShooter() == null
        assertEquals("idle", driver1.state.name(),
            "Should exit ACCEPTING to IDLE when currentShooter becomes null");
    }

    @Test
    @DisplayName("TC-CONDITION_D2: ACCEPTING → IDLE when storage becomes full")
    void testConditionCStorageFull()
    {
        world.loadMap(testMap);

        // Setup two drivers
        Tile driver1Tile = world.tile(10, 10);
        Tile driver2Tile = world.tile(15, 10);
        driver1Tile.setBlock(Blocks.massDriver, Team.sharded);
        driver2Tile.setBlock(Blocks.massDriver, Team.sharded);

        // Access build instances
        MassDriver.MassDriverBuild driver1 = (MassDriver.MassDriverBuild) driver1Tile.build;
        MassDriver.MassDriverBuild driver2 = (MassDriver.MassDriverBuild) driver2Tile.build;

        // Enter ACCEPTING
        driver1.waitingShooters.add(driver2);
        driver1.state = MassDriver.DriverState.accepting;

        updateBuildings(1);

        // Fill storage to leave < 10 space
        for(Item item : content.items())
        {
            int remaining = driver1.block.itemCapacity - 5;  // Only 5 space left
            driver1.items.set(item, remaining);
        }

        updateBuildings(1);

        // Line 170: if(itemCapacity - items.total() < minDistribute)
        assertEquals("idle", driver1.state.name(),
            "Should exit ACCEPTING to IDLE when storage becomes full (< 10 space)");
    }

    @Test
    @DisplayName("TC-CONDITION_E1: SHOOTING → IDLE when link lost")
    void testConditionDLinkLost()
    {
        world.loadMap(testMap);

        // Setup two drivers
        Tile driver1Tile = world.tile(10, 10);
        Tile driver2Tile = world.tile(50, 10);
        driver1Tile.setBlock(Blocks.massDriver, Team.sharded);
        driver2Tile.setBlock(Blocks.massDriver, Team.sharded);

        // Access build instances
        MassDriver.MassDriverBuild driver1 = (MassDriver.MassDriverBuild) driver1Tile.build;
        MassDriver.MassDriverBuild driver2 = (MassDriver.MassDriverBuild) driver2Tile.build;

        // Enter SHOOTING
        driver1.link = driver2.pos();
        driver1.state = MassDriver.DriverState.shooting;
        driver1.items.set(Items.copper, 15);

        updateBuildings(1);

        // Break the link
        driver1.link = -1;

        updateBuildings(1);

        // if(!hasLink)
        assertEquals("idle", driver1.state.name(),
            "Should exit SHOOTING to IDLE when link becomes invalid");
    }

    @Test
    @DisplayName("TC-CONDITION_E2: SHOOTING → IDLE when high priority request arrives")
    void testConditionDPriority()
    {
        world.loadMap(testMap);

        // Setup three drivers to test priority handling
        Tile driver1Tile = world.tile(10, 10);
        Tile driver2Tile = world.tile(50, 10);
        Tile driver3Tile = world.tile(15, 10);
        driver1Tile.setBlock(Blocks.massDriver, Team.sharded);
        driver2Tile.setBlock(Blocks.massDriver, Team.sharded);
        driver3Tile.setBlock(Blocks.massDriver, Team.sharded);

        // Access build instances
        MassDriver.MassDriverBuild driver1 = (MassDriver.MassDriverBuild) driver1Tile.build;
        MassDriver.MassDriverBuild driver2 = (MassDriver.MassDriverBuild) driver2Tile.build;
        MassDriver.MassDriverBuild driver3 = (MassDriver.MassDriverBuild) driver3Tile.build;

        // Enter SHOOTING
        driver1.link = driver2.pos();
        driver1.state = MassDriver.DriverState.shooting;
        driver1.items.set(Items.copper, 15);

        updateBuildings(1);

        // High priority request: driver3 wants to shoot to driver1
        // Setup reverse link from driver3 to driver1 (required for valid shooter)
        driver3.link = driver1.pos();
        driver1.waitingShooters.add(driver3);
        driver1.items.clear();  // Ensure space available (>= 10)

        updateBuildings(1);

        // if(!waitingShooters.isEmpty() && space >= 10)
        assertEquals("idle", driver1.state.name(),
            "Should exit SHOOTING to IDLE when high-priority ACCEPTING request arrives");
    }

    @Test
    @DisplayName("TC-CONDITION_F: SHOOTING → IDLE when all fire conditions complete")
    void testConditionEFireComplete() 
    {
        world.loadMap(testMap);

        // Setup two drivers
        Tile driver1Tile = world.tile(10, 10);
        Tile driver2Tile = world.tile(15, 10);
        driver1Tile.setBlock(Blocks.massDriver, Team.sharded);
        driver2Tile.setBlock(Blocks.massDriver, Team.sharded);

        // Access build instances
        MassDriver.MassDriverBuild driver1 = (MassDriver.MassDriverBuild) driver1Tile.build;
        MassDriver.MassDriverBuild driver2 = (MassDriver.MassDriverBuild) driver2Tile.build;

        // Setup SHOOTING state
        driver1.link = driver2.pos();
        driver1.state = MassDriver.DriverState.shooting;
        driver1.items.set(Items.copper, 15);
        driver2.state = MassDriver.DriverState.accepting;
        driver2.items.clear();  // Space available

        // Setup for firing
        driver1.rotation = 0f;
        driver2.rotation = 180f;
        driver1.reloadCounter = 0f;

        updateBuildings(1);

        // fire(other)
        assertEquals("idle", driver1.state.name(),
            "Shooter should transition to IDLE after fire executes");
    }
}

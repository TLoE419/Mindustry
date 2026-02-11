 # Mindustry Software Testing Report

## 1. Introduction

Mindustry is an open-source automation tower defense real-time strategy (RTS) game that combines strategic gameplay, factory automation, and resource management. The project represents a substantial, production-quality Java codebase with sophisticated cross-platform support and extensive game mechanics.

### 1.1 Project Overview

**Mindustry** is an automation tower defense RTS game where players construct factories, manage complex resource chains, and defend against waves of enemies.

- **Tower Defense Mechanics**: Building defensive structures and turrets
- **Factory Automation**: Complex production chains with items and liquids
- **Resource Management**: Multiple resource types (copper, lead, coal, titanium, etc.)
- **Multiplayer Support**: Both PvP and cooperative modes with networking
- **Multiple platforms**: The game is available on multiple platforms - Windows, Linux, macOS, Android, iOS, and dedicated server deployments.



### 1.2 Technical Specifications

|  |  |
|-----------|-------|
| **Primary Language** | Java |
| **Build Language** | Kotlin/Groovy (Gradle) |
| **Lines of Code** | ~170,000 lines |
| **Java Source Files** | 813 files |
| **Required JDK** | JDK 17 (compilation) |
| **Target Compatibility** | Java 8 (runtime, for Android/iOS) |
| **Build System** | Gradle (multi-module) |
| **Game Framework** | Arc (custom LibGDX-based framework) |

### 1.3 Project Architecture

The codebase follows a multi-module Gradle architecture:

```
Mindustry/
├── core/           # Main game engine and logic (~160,000 LOC)
├── desktop/        # Desktop platform implementation
├── server/         # Headless server implementation
├── android/        # Android mobile platform
├── ios/            # iOS mobile platform
├── tools/          # Asset packing and sprite generation
├── tests/          # JUnit 5 test suite
└── annotations/    # Custom annotation processors
```

---

## 2. Build Documentation

### 2.1 Prerequisites

Building Mindustry requires:

- **JDK 17**
- **Gradle** 

### 2.2 Build Commands

#### Desktop Application

```bash
# Windows
gradlew desktop:run          # Run the game
gradlew desktop:dist         # Build distributable JAR

# Linux/macOS
./gradlew desktop:run
./gradlew desktop:dist
```

#### Running Tests

```bash
# Windows
gradlew tests:test

# Linux/macOS
./gradlew tests:test
```
---

## 3. Existing Test Infrastructure

### 3.1 Testing Framework

Mindustry uses **JUnit 5** as its primary testing framework


### 3.2 Test Organization

```
tests/src/test/
├── java/
│   ├── ApplicationTests.java           # 60+ integration tests
│   ├── PatcherTests.java               # 50+ content patcher tests
│   ├── GenericModTest.java             # Base mod testing class
│   ├── ModTestAllure.java              # Mod loading tests
│   └── power/
│       ├── PowerTestFixture.java       # Shared test utilities
│       ├── PowerTests.java             # Power graph tests
│       ├── DirectConsumerTests.java    # Direct consumer tests
│       └── ConsumeGeneratorTests.java  # Generator tests
└── resources/
```

### 3.3 Existing Test cases 

#### Integration Tests (`ApplicationTests.java`)

The primary test class initializes the complete game environment in headless mode and tests:

- Block placement and multiblock structures
- Routing systems
- Wave spawning mechanics
- Save and load game

Example test pattern:

```java
@Test
    void saveLoad()
    {
        world.loadMap(testMap);
        Map map = state.map;

        float hp = 30f;

        Unit unit = UnitTypes.dagger.spawn(Team.sharded, 20f, 30f);
        unit.health = hp;

        SaveIO.save(saveDirectory.child("0.msav"));
        resetWorld();
        SaveIO.load(saveDirectory.child("0.msav"));

        Unit spawned = Groups.unit.find(u -> u.type == UnitTypes.dagger);
        assertNotNull(spawned, "Saved daggers must persist");
        assertEquals(hp, spawned.health, "Spawned dagger health must save.");

        assertEquals(world.width(), map.width);
        assertEquals(world.height(), map.height);
        assertTrue(state.teams.playerCores().size > 0);
    }
```

---

## 4. Partition Testing

### 4.1 Motivation

- **Systematic Functional Testing:** Verify functionality based on specifications. Only care about given input and does it produce correct output. Try to select inputs that are especially valuable. The creation of test cases is informed by the intended functionality or behaviors of the software. 
- **Partition testing:** addresses this challenge by systematically dividing the input domain into equivalence classes (partitions) where all inputs within a partition are expected to behave similarly. The key insight is that if the program handles one value from a partition correctly, it likely handles all values in that partition correctly.

---

## 5. Partition Testing Implementations

### 5.1 Feature: Save/Load System (Unit Persistence)

**File:** `ApplicationTests.java` lines 377-433

**Feature Description:** The Save/Load system (`SaveIO.java`) persists game state including units. The existing `saveLoad()` test only covers a single unit.

### 5.2 Existing Test Analysis

The original `saveLoad()` test:
```java
void saveLoad(){
    world.loadMap(testMap);
    float hp = 30f;
    Unit unit = UnitTypes.dagger.spawn(Team.sharded, 20f, 30f);
    unit.health = hp;

    SaveIO.save(saveDirectory.child("0.msav"));
    resetWorld();
    SaveIO.load(saveDirectory.child("0.msav"));

    Unit spawned = Groups.unit.find(u -> u.type == UnitTypes.dagger);
    assertNotNull(spawned, "Saved daggers must persist");
    assertEquals(hp, spawned.health, "Spawned dagger health must save.");
}
```

**Limitation:** Only tests 1 unit of 1 type.

### 5.3 Partitioning Scheme

**Input Domain: Unit Count and Type**

| Description | Representative Value |
|-------------|---------------------|
| Multiple units, same type | 5 daggers |
| Multiple units, different types | 1 dagger + 2 nova + 3 flare |

**How Partitions Differ:**
- **Multiple units, same type:** Tests that the system correctly saves and restores multiple instances of the same unit type. This verifies data handling.
- **Multiple units, different types:** Tests that the system correctly differentiates between unit types when saving and loading. Each type should maintain its correct count.

### 5.4 Representative Values Selection

**Multiple units, same type**
- **Value:** 5 dagger units at positions (20,30), (30,40), (40,50), (50,60), (60,70)
- **Reason:** 5 is enough to test collection handling without being excessive. Different positions ensure units are distinct.

**Multiple units, different types**
- **Value:** 1 dagger + 2 nova + 3 flare
- **Reason:** Using different counts (1, 2, 3) for each type makes it easy to verify that each type's count is preserved correctly. If the system confused types, the counts would not match.

### 5.5 New Test Cases

#### Test 1: Multiple Units, Same Type 

```java
/** Partition : Multiple units of the same type */
@Test
void saveLoadMultipleUnitsSameType(){
    world.loadMap(testMap);

    int unitCount = 5;
    for(int i = 0; i < unitCount; i++){
        UnitTypes.dagger.spawn(Team.sharded, 20f + i * 10f, 30f + i * 10f);
    }

    assertEquals(unitCount, Groups.unit.count(u -> u.type == UnitTypes.dagger));

    SaveIO.save(saveDirectory.child("test_multi_same.msav"));
    resetWorld();
    SaveIO.load(saveDirectory.child("test_multi_same.msav"));

    assertEquals(unitCount, Groups.unit.count(u -> u.type == UnitTypes.dagger),
        "All daggers should persist");
}
```

**What it tests:** Verifies that all 5 units of the same type (dagger) are correctly saved and restored.

#### Test 2: Multiple Units, Different Types

```java
/** Partition : Multiple units of different types */
@Test
void saveLoadMultipleUnitsDifferentTypes(){
    world.loadMap(testMap);

    UnitTypes.dagger.spawn(Team.sharded, 50f, 50f);        // 1 dagger
    UnitTypes.nova.spawn(Team.sharded, 70f, 70f);          // 2 nova
    UnitTypes.nova.spawn(Team.sharded, 80f, 80f);
    UnitTypes.flare.spawn(Team.sharded, 90f, 90f);         // 3 flare
    UnitTypes.flare.spawn(Team.sharded, 100f, 100f);
    UnitTypes.flare.spawn(Team.sharded, 110f, 110f);

    SaveIO.save(saveDirectory.child("test_multi_diff.msav"));
    resetWorld();
    SaveIO.load(saveDirectory.child("test_multi_diff.msav"));

    assertEquals(1, Groups.unit.count(u -> u.type == UnitTypes.dagger), "Dagger should persist");
    assertEquals(2, Groups.unit.count(u -> u.type == UnitTypes.nova), "Nova should persist");
    assertEquals(3, Groups.unit.count(u -> u.type == UnitTypes.flare), "Flare should persist");
}
```

**What it tests:** Verifies that different unit types maintain their individual counts after save/load. The different counts (1, 2, 3) make it easy to detect if types get mixed up.

### 5.6 Boundary Value Analysis

For the **Unit Count** input domain, we identify the following boundaries:

| Boundary | Value | Description |
|----------|-------|-------------|
| Lower bound | 0 | Empty unit list (no units) |
| Just above lower | 1 | Minimum non-empty (single unit) |

**Why these boundaries matter:**
- **0 units:** Tests empty collection handling - the system should not crash or create phantom units
- **1 unit:** Tests the minimum non-empty case - already covered by existing `saveLoad()` test

### 5.7 Boundary Value Test Cases

#### Test 1: Zero Units (Lower Boundary)

```java
/** Boundary: Lower bound - 0 units (empty unit list) */
@Test
void saveLoadZeroUnits(){
    world.loadMap(testMap);

    Groups.unit.clear();
    assertEquals(0, Groups.unit.size(), "Precondition: should have 0 units");

    SaveIO.save(saveDirectory.child("test_zero_units.msav"));
    resetWorld();
    SaveIO.load(saveDirectory.child("test_zero_units.msav"));

    assertEquals(0, Groups.unit.size(), "Zero units should persist as zero");
}
```

**What it tests:** Verifies that an empty unit list saves and loads correctly without creating any phantom units.

#### Test 2: One Unit (Just Above Lower Boundary)

```java
/** Boundary: Just above lower bound - exactly 1 unit */
@Test
void saveLoadOneUnit(){
    world.loadMap(testMap);

    Groups.unit.clear();
    UnitTypes.dagger.spawn(Team.sharded, 50f, 50f);
    assertEquals(1, Groups.unit.size(), "Precondition: should have exactly 1 unit");

    SaveIO.save(saveDirectory.child("test_one_unit.msav"));
    resetWorld();
    SaveIO.load(saveDirectory.child("test_one_unit.msav"));

    assertEquals(1, Groups.unit.size(), "Exactly 1 unit should persist");
}
```

### 5.8 Running the Tests

```bash
# Run all partition and boundary tests
./gradlew tests:test --tests "ApplicationTests.saveLoad*"
```


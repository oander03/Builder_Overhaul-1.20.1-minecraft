# Build Previewer - AI Codebase Guide

## Purpose

**Build Previewer** is a Minecraft Forge mod for 1.20.1 (`build_previewer`, Java package `net.hydroset.buildpreviewer`). It lets a player use a Builder Access Block as a private build anchor, temporarily enter a creative-like preview mode, make a structure in the real world, and then either keep the build by supplying the listed materials or discard it and restore the pre-preview world. The mod records block-state snapshots, shows the planned changes as tinted holograms, calculates a material list, and persists the plan and recovery data on the anchor block entity.

The server is authoritative for world edits, snapshot state, inventory requirements, and session transitions. Client code is responsible for the builder screens/HUD, hologram rendering, and sending user actions to the server. Preview mode is not a separate copy of the world: planned block states are temporarily placed into the actual level and then rolled back when the session ends.

## Project at a glance

- **Minecraft:** 1.20.1
- **Forge:** 47.4.20 (range `[47,)`)
- **Java:** 17
- **Mod ID:** `build_previewer`
- **Gradle project:** ForgeGradle 6, Parchment mappings
- **Main source package:** `src/main/java/net/hydroset/buildpreviewer`
- **Resources:** `src/main/resources`
- **Optional JEI integration:** the Java class is currently commented out, and JEI dependencies in `build.gradle` are commented out too. Do not assume it is active or available at runtime.

## Main user flow

1. The player opens the Builder Access Block. Its block entity provides the preview menu and stores the build plan, item buffer, owner, and recovery values.
2. The menu's preview action sends `TogglePreviewPacket`. The server calls `PreviewManager.enterPreview`: it restores the saved plan to the world, remembers the player's inventory/game mode and anchor, clears the player's inventory, switches to creative, and sends hologram data.
3. During preview, placement and breaking events are recorded as per-position `(originalState, buildState)` snapshots. A survival player's existing world edits are also observed so pending plans can keep their true original state.
4. The server computes required items from the difference between original and planned block states. The UI displays that shopping list and accepts matching items into the anchor's 108-slot internal buffer. The screen and HUD include scroll, progress, placed/broken counters, hologram controls, and clear/remove actions.
5. Finalize sends `FinalizeBuildPacket`. `PreviewBlockEntity.checkRequirementsAndCommit` applies removals for free and applies placeable planned blocks when the matching item is available, consuming materials. Incomplete snapshots remain on the anchor.
6. Exiting preview restores the original world states, player inventory and game mode, clears the active session, and leaves the plan/requirements available at the anchor. Logout/server recovery persists enough information to roll back and close a stale session.

## State and ownership model

- `PreviewManager.pendingCommit` is the in-memory per-player build plan (`UUID -> BlockPos -> BuildSnapshot`); `sessionChanges` holds original states first captured during the current active session.
- A `BuildSnapshot` has a mutable `originalState` and a planned `buildState`. The original state is the rollback target; the build state is the state previewed/finalized.
- `PreviewBlockEntity` persists snapshots, requirements, its 108-slot material inventory, owner UUID, saved player inventory, and saved game mode in NBT. It is also the menu provider and item-handler capability owner.
- `PreviewSavedData` persists player UUID to anchor position across server restarts. The block entity stores detailed build/session state; SavedData allows login recovery to find the anchor.
- `PreviewManager.isInPreview(UUID)` is determined by presence in the in-memory player-to-anchor map. Do not confuse that live-session flag with a saved, inactive plan.
- Beds, doors, and double-height plants have a primary and a secondary block state. The manager avoids charging for their secondary half and the event code captures the paired state when breaking them.
- Required-item calculation accounts for multi-count block states such as double slabs, candles, sea pickles, and turtle eggs. Finalization works per planned position and may leave unplaceable positions pending.

## Source tree and file responsibilities

### Root package: lifecycle, state, and world events

| File | Responsibility |
|---|---|
| `BuildPreviewer.java` | Forge entry point (`@Mod`). Registers item, block, block entity, menu type, setup listeners, both network channels, the creative-tab entries, preview screen, and the cutout render layer for the access block. Common/client setup and server-start handlers are otherwise mostly empty. |
| `Config.java` | Forge configuration example with sample “magic number” and item-list settings. The config registration in `BuildPreviewer` is commented out, so these values are not currently part of the mod's active configuration. |
| `PreviewManager.java` | Central server-side preview/session service. Owns in-memory maps for snapshots, session originals, anchor positions, prior game modes, inventories, paired blocks, and rate/bookkeeping state. Implements entering/exiting, record/restore/rehydrate, inventory swapping, required-item calculations, hologram updates, safe teleport selection, and session cleanup. |
| `PreviewEvents.java` | Forge event subscriber for preview safety and world-state capture. Guards interactions/damage/dimension changes/unsafe items and blocks, protects active anchors and planned positions, records block placements/breaks (including double blocks), syncs changes, handles logout/login recovery, and sends a build sync request when a client screen opens. |
| `PreviewSavedData.java` | World SavedData mapping player UUIDs to anchor positions. Saves/loads those positions for login recovery and marks changes dirty on update/removal. |
| `ClientRenderEvents.java` | Client-side renderer for outline/rim effects around changed preview blocks. Draws exposed faces and suppresses internal/shared faces between adjacent outline positions to reduce z-fighting. |

### `block/`: anchor block

| File | Responsibility |
|---|---|
| `block/ModBlocks.java` | Deferred block registration for `builder_access_block`; configures its sound, light level based on active state, post-processing, and matching block item registration. |
| `block/PreviewBlock.java` | The Builder Access Block implementation. Has an `active` state, creates its block entity, opens the authorized player's menu, prevents breaking while active, emits active particles, drops buffered items when removed, and contains the helper used to apply finalized build snapshots. |

### `block/entity/`: anchor data, menu provider, and material buffer

| File | Responsibility |
|---|---|
| `block/entity/ModBlockEntities.java` | Registers `PreviewBlockEntity` for the Builder Access Block. |
| `block/entity/PreviewBlockEntity.java` | Anchor's persistent data and inventory implementation. Owns build snapshots, required-item counts, owner/access checks, pending placed/broken counts, the 108-slot item handler, NBT/update synchronization, inventory/game-mode recovery data, item drops, and finalize/commit behavior. Exposes a normal internal item-handler capability but blocks external hopper/pipeline insertion and extraction. |

### `hologram/`: client preview overlay channel and renderer

| File | Responsibility |
|---|---|
| `hologram/HologramRenderer.java` | Client level-stage renderer for hologram snapshots. Renders placed blocks cyan/blue and removed blocks red with tint/transparency, ordering entries back-to-front and filtering shared internal faces. Includes custom tinted buffer/vertex wrappers and special handling for block-entity renderers. |
| `hologram/HologramSyncPacket.java` | Server-to-client hologram payload: map of block position to block state plus placed/broken flag. Encodes/decodes block-state IDs and updates the client renderer on the client thread. |
| `hologram/ModNetwork.java` | Registers the separate strict-version `main` SimpleChannel for server-to-client `HologramSyncPacket` traffic. |

### `item/`: item registration

| File | Responsibility |
|---|---|
| `item/ModItems.java` | Deferred item registry and the standalone `builder_access` item. The block item for `builder_access_block` is registered by `ModBlocks`. |

### `jei/`: optional integration stub

| File | Responsibility |
|---|---|
| `jei/BuildPreviewerJeiPlugin.java` | Commented-out JEI plugin draft. It would reserve the full screen as a JEI extra area while preview/menu screens are active, but it currently compiles as comments only and has no active runtime behavior. |

### `networking/`: client-to-server actions and build synchronization

| File | Responsibility |
|---|---|
| `networking/ModMessages.java` | Registers the `messages` SimpleChannel and the six client-to-server message types; provides send-to-server and send-to-player helpers. |
| `networking/TogglePreviewPacket.java` | Carries the anchor position and asks the server to enter preview or exit the sender's current preview session. |
| `networking/FinalizeBuildPacket.java` | Carries the anchor position and asks that anchor block entity to check buffered materials and finalize eligible build changes. |
| `networking/ServerboundScrollPacket.java` | Sends the requested scroll row to the server-side `PreviewMenu` so its 27 visible slots refer to the correct inventory entries. |
| `networking/RemoveRequiredItemPacket.java` | Removes all planned positions whose planned block maps to the selected item, restores their originals, updates requirements/session bookkeeping, and refreshes holograms and block-entity data. |
| `networking/ClearAllBlocksPacket.java` | Rolls back every pending planned position for the sender, clears session/build snapshots and requirements, and clears hologram data. |
| `networking/RequestBuildSyncPacket.java` | Empty request payload; server finds the sender's active anchor and refreshes its stored build data from the live pending plan. |

### `screen/`: menu, inventory GUI, and preview HUD

| File | Responsibility |
|---|---|
| `screen/ModMenuTypes.java` | Deferred registration of the `preview_menu` container type. |
| `screen/PreviewMenu.java` | Server/client container. Provides a 27-slot scrolling window over the anchor requirements, validates inserted stacks against the required item at each index, blocks material removal/shift-click while in preview, handles quick-move transfer, and adds the player's inventory/hotbar. |
| `screen/ScrollableSlot.java` | Slot adapter whose active backing index can change as the menu scrolls. Reads/writes/removes from the corresponding `IItemHandler` slot and supports oversized requirement counts. |
| `screen/PreviewScreen.java` | Client menu GUI. Renders the blueprint/material grid, count/progress and placed/broken displays, scroll bar, animated fill/wave/bubble/completion effects, tooltips, and action buttons for preview/finalize/hologram/clear. Handles mouse/scroll input and sends packets for server actions. |
| `screen/PreviewHudOverlay.java` | Client overlay while building. Adds required-item counts/icons, preview exit, hologram and clear controls, placed/broken counter, and a time-of-day slider. Handles cached/animated counts, GUI click actions, and transparent-menu background composition. |

## Non-Java project structure

```text
build.gradle                 ForgeGradle setup, Java toolchain, dependency and jar configuration
gradle.properties            MC/Forge/mappings/mod metadata properties
settings.gradle              Gradle plugin repositories and toolchain resolver
gradlew, gradlew.bat         Gradle wrapper launchers
src/main/java/...            Mod implementation (inventory above)
src/main/resources/
  META-INF/mods.toml         Forge mod identity and Minecraft/Forge dependencies
  pack.mcmeta                Resource pack metadata (pack format 15)
  assets/build_previewer/    Client presentation resources
  data/build_previewer/      Mod recipe and block loot table
  data/minecraft/tags/       Mining/tool tag membership for the anchor block
README.txt, changelog.txt    Project notes and change history
CREDITS.txt, LICENSE.txt     Attribution and license
build/, run/                 Build output and local Forge development runtime
```

`.gitignore` and `.gitattributes` hold repository settings; `.gradle/` and `.idea/` are local Gradle/IDE state rather than mod runtime code.

### Resource files

- `assets/build_previewer/blockstates/builder_access_block.json`: chooses the access-block model based on block state.
- `assets/build_previewer/models/block/access_block_off.json`, `access_block_on.json`: inactive/active block models.
- `assets/build_previewer/models/item/builder_access.json`, `builder_access_block.json`: item models.
- `assets/build_previewer/lang/en_us.json`: display names for the Builder Access item and block.
- `assets/build_previewer/textures/block/access_block_bottom.png`, `access_block_side.png`, `access_block_top_off.png`, `access_block_top_on.png`: base block textures.
- `assets/build_previewer/textures/block/access_block_bottom_e.png`, `access_block_side_e.png`, `access_block_top_off_e.png`, `access_block_top_on_e.png` and their `.png.mcmeta` files: emissive overlays and animation metadata.
- `assets/build_previewer/textures/item/builder_access.png`: Builder Access item texture.
- `assets/build_previewer/textures/gui/preview_block_gui.png`, `preview_block_gui_active.png`, `preview_block_gui_scroll.png`, `preview_block_gui_scroll_active.png`, `single_slot_gui.png`, `container/scroll_bar.png`: preview menu and scroll visuals.
- `data/build_previewer/recipes/builder_access_block_recipe.json`: shaped recipe (blackstone surrounding an emerald).
- `data/build_previewer/loot_tables/blocks/builder_access_block.json`: drops the block when it survives an explosion.
- `data/minecraft/tags/blocks/needs_stone_tool.json`, `mineable/pickaxe.json`: add the access block to stone-tool and pickaxe tags. The corresponding `needs_diamond_tool.json`, `needs_iron_tool.json`, `mineable/axe.json`, `mineable/hoe.json`, and `mineable/shovel.json` files are empty and add no block memberships.

## Navigation hints for future changes

- For a lifecycle or snapshot bug, start with `PreviewManager`, then trace the relevant Forge hook in `PreviewEvents` or action packet in `networking/`.
- For material counts/finalization/persistence, start with `PreviewBlockEntity`; requirement calculation and `BuildSnapshot` are in `PreviewManager`.
- For a UI action, trace `PreviewScreen` or `PreviewHudOverlay` to `ModMessages` and its packet handler. World mutation should remain on the server.
- For a missing hologram, check `PreviewManager.sendHologramUpdate`, `ModNetwork` registration, `HologramSyncPacket`, and `HologramRenderer`.
- There are two SimpleChannels: `ModMessages` (`build_previewer:messages`) for builder actions and `ModNetwork` (`build_previewer:main`) for hologram sync.
- `Config` and `BuildPreviewerJeiPlugin` are currently inactive scaffolding; check registration/dependencies before relying on them.

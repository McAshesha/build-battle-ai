package ru.ashesha.buildBattleAI.render.data;

import com.cryptomorin.xseries.XMaterial;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link LegacyBlockStates} — legacy (1.8–1.12) block data to modern
 * block state string conversion.
 * <p>
 * Each conversion method is tested for all relevant data bit combinations
 * to ensure correct facing, half, open, axis, and rotation values.
 */
class LegacyBlockStatesTest {

    // ===== toBlockState dispatch =====

    @Test
    void returnsNullForBlockWithNoState() {
        assertNull(LegacyBlockStates.toBlockState(XMaterial.STONE, (byte) 0));
    }

    @Test
    void returnsNullForAir() {
        assertNull(LegacyBlockStates.toBlockState(XMaterial.AIR, (byte) 0));
    }

    // ===== Stairs =====

    @Test
    void stairsFacingEastBottomHalf() {
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_STAIRS, (byte) 0);
        assertEquals("minecraft:oak_stairs[facing=east,half=bottom,shape=straight]", state);
    }

    @Test
    void stairsFacingWestBottomHalf() {
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_STAIRS, (byte) 1);
        assertEquals("minecraft:oak_stairs[facing=west,half=bottom,shape=straight]", state);
    }

    @Test
    void stairsFacingSouthBottomHalf() {
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_STAIRS, (byte) 2);
        assertEquals("minecraft:oak_stairs[facing=south,half=bottom,shape=straight]", state);
    }

    @Test
    void stairsFacingNorthBottomHalf() {
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_STAIRS, (byte) 3);
        assertEquals("minecraft:oak_stairs[facing=north,half=bottom,shape=straight]", state);
    }

    @Test
    void stairsFacingEastTopHalf() {
        // bit 2 set = top
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_STAIRS, (byte) 4);
        assertEquals("minecraft:oak_stairs[facing=east,half=top,shape=straight]", state);
    }

    @Test
    void stairsFacingNorthTopHalf() {
        // data 7 = bits 0-1 = 3 (north), bit 2 = 1 (top)
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_STAIRS, (byte) 7);
        assertEquals("minecraft:oak_stairs[facing=north,half=top,shape=straight]", state);
    }

    @Test
    void stairsDifferentMaterial() {
        String state = LegacyBlockStates.toBlockState(XMaterial.COBBLESTONE_STAIRS, (byte) 2);
        assertEquals("minecraft:cobblestone_stairs[facing=south,half=bottom,shape=straight]", state);
    }

    // ===== Slabs =====

    @Test
    void slabBottomPosition() {
        String state = LegacyBlockStates.toBlockState(XMaterial.STONE_SLAB, (byte) 0);
        assertEquals("minecraft:stone_slab[type=bottom]", state);
    }

    @Test
    void slabTopPosition() {
        // bit 3 set = top
        String state = LegacyBlockStates.toBlockState(XMaterial.STONE_SLAB, (byte) 8);
        assertEquals("minecraft:stone_slab[type=top]", state);
    }

    @Test
    void slabDifferentMaterial() {
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_SLAB, (byte) 0);
        assertEquals("minecraft:oak_slab[type=bottom]", state);
    }

    // ===== Trapdoors =====

    @Test
    void trapdoorFacingSouthClosedBottom() {
        // bits 0-1 = 0 → south, bit 2 = 0 (closed), bit 3 = 0 (bottom)
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_TRAPDOOR, (byte) 0);
        assertEquals("minecraft:oak_trapdoor[facing=south,half=bottom,open=false]", state);
    }

    @Test
    void trapdoorFacingNorthClosedBottom() {
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_TRAPDOOR, (byte) 1);
        assertEquals("minecraft:oak_trapdoor[facing=north,half=bottom,open=false]", state);
    }

    @Test
    void trapdoorFacingEastClosedBottom() {
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_TRAPDOOR, (byte) 2);
        assertEquals("minecraft:oak_trapdoor[facing=east,half=bottom,open=false]", state);
    }

    @Test
    void trapdoorFacingWestClosedBottom() {
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_TRAPDOOR, (byte) 3);
        assertEquals("minecraft:oak_trapdoor[facing=west,half=bottom,open=false]", state);
    }

    @Test
    void trapdoorOpenFlag() {
        // bit 2 = open
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_TRAPDOOR, (byte) 4);
        assertEquals("minecraft:oak_trapdoor[facing=south,half=bottom,open=true]", state);
    }

    @Test
    void trapdoorTopHalf() {
        // bit 3 = top
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_TRAPDOOR, (byte) 8);
        assertEquals("minecraft:oak_trapdoor[facing=south,half=top,open=false]", state);
    }

    @Test
    void trapdoorAllBitsSet() {
        // 0xF = facing=west(3), open=true(4), top(8)
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_TRAPDOOR, (byte) 15);
        assertEquals("minecraft:oak_trapdoor[facing=west,half=top,open=true]", state);
    }

    // ===== Doors =====

    @Test
    void doorLowerHalfFacingEastClosed() {
        // bits 0-1 = 0 (east), bit 2 = 0 (closed), bit 3 = 0 (lower)
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_DOOR, (byte) 0);
        assertEquals("minecraft:oak_door[facing=east,half=lower,open=false]", state);
    }

    @Test
    void doorLowerHalfFacingSouthClosed() {
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_DOOR, (byte) 1);
        assertEquals("minecraft:oak_door[facing=south,half=lower,open=false]", state);
    }

    @Test
    void doorLowerHalfFacingWestClosed() {
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_DOOR, (byte) 2);
        assertEquals("minecraft:oak_door[facing=west,half=lower,open=false]", state);
    }

    @Test
    void doorLowerHalfFacingNorthClosed() {
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_DOOR, (byte) 3);
        assertEquals("minecraft:oak_door[facing=north,half=lower,open=false]", state);
    }

    @Test
    void doorLowerHalfOpen() {
        // bit 2 = open
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_DOOR, (byte) 4);
        assertEquals("minecraft:oak_door[facing=east,half=lower,open=true]", state);
    }

    @Test
    void doorLowerHalfFacingNorthOpen() {
        // bits 0-1 = 3 (north), bit 2 = 1 (open)
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_DOOR, (byte) 7);
        assertEquals("minecraft:oak_door[facing=north,half=lower,open=true]", state);
    }

    @Test
    void doorUpperHalfDefaultsFacing() {
        // bit 3 set → upper half, facing defaults to north
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_DOOR, (byte) 8);
        assertEquals("minecraft:oak_door[half=upper,facing=north,open=false]", state);
    }

    @Test
    void doorUpperHalfWithHingeBit() {
        // bit 3 + bit 0 (hinge) → still defaults facing to north
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_DOOR, (byte) 9);
        assertEquals("minecraft:oak_door[half=upper,facing=north,open=false]", state);
    }

    // ===== Fence gates =====

    @Test
    void fenceGateFacingSouthClosed() {
        // bits 0-1 = 0 → south
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_FENCE_GATE, (byte) 0);
        assertEquals("minecraft:oak_fence_gate[facing=south,open=false]", state);
    }

    @Test
    void fenceGateFacingWestClosed() {
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_FENCE_GATE, (byte) 1);
        assertEquals("minecraft:oak_fence_gate[facing=west,open=false]", state);
    }

    @Test
    void fenceGateFacingNorthClosed() {
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_FENCE_GATE, (byte) 2);
        assertEquals("minecraft:oak_fence_gate[facing=north,open=false]", state);
    }

    @Test
    void fenceGateFacingEastClosed() {
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_FENCE_GATE, (byte) 3);
        assertEquals("minecraft:oak_fence_gate[facing=east,open=false]", state);
    }

    @Test
    void fenceGateOpen() {
        // bit 2 = open
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_FENCE_GATE, (byte) 4);
        assertEquals("minecraft:oak_fence_gate[facing=south,open=true]", state);
    }

    @Test
    void fenceGateNorthOpen() {
        // bits 0-1 = 2 (north), bit 2 (open)
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_FENCE_GATE, (byte) 6);
        assertEquals("minecraft:oak_fence_gate[facing=north,open=true]", state);
    }

    // ===== Standing signs =====

    @Test
    void standingSignRotation0() {
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_SIGN, (byte) 0);
        assertEquals("minecraft:oak_sign[rotation=0]", state);
    }

    @Test
    void standingSignRotation8() {
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_SIGN, (byte) 8);
        assertEquals("minecraft:oak_sign[rotation=8]", state);
    }

    @Test
    void standingSignRotation15() {
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_SIGN, (byte) 15);
        assertEquals("minecraft:oak_sign[rotation=15]", state);
    }

    // ===== Wall-mounted blocks =====

    @Test
    void wallSignFacingNorth() {
        // data 2 → north
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_WALL_SIGN, (byte) 2);
        assertEquals("minecraft:oak_wall_sign[facing=north]", state);
    }

    @Test
    void wallSignFacingSouth() {
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_WALL_SIGN, (byte) 3);
        assertEquals("minecraft:oak_wall_sign[facing=south]", state);
    }

    @Test
    void wallSignFacingWest() {
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_WALL_SIGN, (byte) 4);
        assertEquals("minecraft:oak_wall_sign[facing=west]", state);
    }

    @Test
    void wallSignFacingEast() {
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_WALL_SIGN, (byte) 5);
        assertEquals("minecraft:oak_wall_sign[facing=east]", state);
    }

    @Test
    void wallMountedOutOfRangeDefaultsToNorth() {
        // data 0 is not in range 2-5 → defaults to north
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_WALL_SIGN, (byte) 0);
        assertEquals("minecraft:oak_wall_sign[facing=north]", state);
    }

    @Test
    void wallMountedData1DefaultsToNorth() {
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_WALL_SIGN, (byte) 1);
        assertEquals("minecraft:oak_wall_sign[facing=north]", state);
    }

    @Test
    void ladderFacingNorth() {
        String state = LegacyBlockStates.toBlockState(XMaterial.LADDER, (byte) 2);
        assertEquals("minecraft:ladder[facing=north]", state);
    }

    @Test
    void wallTorchFacingSouth() {
        String state = LegacyBlockStates.toBlockState(XMaterial.WALL_TORCH, (byte) 3);
        assertEquals("minecraft:wall_torch[facing=south]", state);
    }

    @Test
    void soulWallTorchFacingWest() {
        String state = LegacyBlockStates.toBlockState(XMaterial.SOUL_WALL_TORCH, (byte) 4);
        assertEquals("minecraft:soul_wall_torch[facing=west]", state);
    }

    @Test
    void redstoneWallTorchFacingEast() {
        String state = LegacyBlockStates.toBlockState(XMaterial.REDSTONE_WALL_TORCH, (byte) 5);
        assertEquals("minecraft:redstone_wall_torch[facing=east]", state);
    }

    // ===== Axis-aligned blocks =====

    @Test
    void logAxisY() {
        // (data >> 2) & 3 = 0 → y
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_LOG, (byte) 0);
        assertEquals("minecraft:oak_log[axis=y]", state);
    }

    @Test
    void logAxisX() {
        // (4 >> 2) & 3 = 1 → x
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_LOG, (byte) 4);
        assertEquals("minecraft:oak_log[axis=x]", state);
    }

    @Test
    void logAxisZ() {
        // (8 >> 2) & 3 = 2 → z
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_LOG, (byte) 8);
        assertEquals("minecraft:oak_log[axis=z]", state);
    }

    @Test
    void logAxisOutOfRangeDefaultsToY() {
        // (12 >> 2) & 3 = 3 → out of AXIS_VALUES range → "y"
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_LOG, (byte) 12);
        assertEquals("minecraft:oak_log[axis=y]", state);
    }

    @Test
    void boneBlockAxisX() {
        String state = LegacyBlockStates.toBlockState(XMaterial.BONE_BLOCK, (byte) 4);
        assertEquals("minecraft:bone_block[axis=x]", state);
    }

    @Test
    void hayBlockAxisZ() {
        String state = LegacyBlockStates.toBlockState(XMaterial.HAY_BLOCK, (byte) 8);
        assertEquals("minecraft:hay_block[axis=z]", state);
    }

    @Test
    void purpurPillarAxisY() {
        String state = LegacyBlockStates.toBlockState(XMaterial.PURPUR_PILLAR, (byte) 0);
        assertEquals("minecraft:purpur_pillar[axis=y]", state);
    }

    @Test
    void quartzPillarAxisX() {
        String state = LegacyBlockStates.toBlockState(XMaterial.QUARTZ_PILLAR, (byte) 4);
        assertEquals("minecraft:quartz_pillar[axis=x]", state);
    }

    @Test
    void endRodAxisY() {
        String state = LegacyBlockStates.toBlockState(XMaterial.END_ROD, (byte) 0);
        assertEquals("minecraft:end_rod[axis=y]", state);
    }

    @Test
    void logSubTypeBitsIgnoredForAxis() {
        // data 5 = bits 0-1 = 1 (sub-type), bits 2-3 = 01 (x)
        // Sub-type bits should be ignored for axis calculation
        String state = LegacyBlockStates.toBlockState(XMaterial.OAK_LOG, (byte) 5);
        assertEquals("minecraft:oak_log[axis=x]", state);
    }

    // ===== Snow =====

    @Test
    void snowLayers1() {
        // (0 & 7) + 1 = 1
        String state = LegacyBlockStates.toBlockState(XMaterial.SNOW, (byte) 0);
        assertEquals("minecraft:snow[layers=1]", state);
    }

    @Test
    void snowLayers4() {
        String state = LegacyBlockStates.toBlockState(XMaterial.SNOW, (byte) 3);
        assertEquals("minecraft:snow[layers=4]", state);
    }

    @Test
    void snowLayers8() {
        // (7 & 7) + 1 = 8
        String state = LegacyBlockStates.toBlockState(XMaterial.SNOW, (byte) 7);
        assertEquals("minecraft:snow[layers=8]", state);
    }

    // ===== Vines =====

    @Test
    void vineNorthBit() {
        // bit 2 (=4) → north
        String state = LegacyBlockStates.toBlockState(XMaterial.VINE, (byte) 4);
        assertEquals("minecraft:vine[facing=north]", state);
    }

    @Test
    void vineSouthBit() {
        // bit 0 (=1) → south
        String state = LegacyBlockStates.toBlockState(XMaterial.VINE, (byte) 1);
        assertEquals("minecraft:vine[facing=south]", state);
    }

    @Test
    void vineWestBit() {
        // bit 1 (=2) → west
        String state = LegacyBlockStates.toBlockState(XMaterial.VINE, (byte) 2);
        assertEquals("minecraft:vine[facing=west]", state);
    }

    @Test
    void vineEastBit() {
        // bit 3 (=8) → east
        String state = LegacyBlockStates.toBlockState(XMaterial.VINE, (byte) 8);
        assertEquals("minecraft:vine[facing=east]", state);
    }

    @Test
    void vineNoBitsDefaultsToNorth() {
        String state = LegacyBlockStates.toBlockState(XMaterial.VINE, (byte) 0);
        assertEquals("minecraft:vine[facing=north]", state);
    }

    @Test
    void vineNorthTakesPriorityOverSouth() {
        // Both north (bit 2) and south (bit 0) set → north wins (checked first)
        String state = LegacyBlockStates.toBlockState(XMaterial.VINE, (byte) 5);
        assertEquals("minecraft:vine[facing=north]", state);
    }

    @Test
    void vineSouthTakesPriorityOverWest() {
        // Both south (bit 0) and west (bit 1) set → south wins
        String state = LegacyBlockStates.toBlockState(XMaterial.VINE, (byte) 3);
        assertEquals("minecraft:vine[facing=south]", state);
    }

    @Test
    void vineWestTakesPriorityOverEast() {
        // Both west (bit 1) and east (bit 3) set → west wins
        String state = LegacyBlockStates.toBlockState(XMaterial.VINE, (byte) 10);
        assertEquals("minecraft:vine[facing=west]", state);
    }

    // ===== Unsigned byte handling =====

    @Test
    void negativeByteIsTreatedAsUnsigned() {
        // (byte) -1 → 0xFF → 255 unsigned
        // For snow: (255 & 7) + 1 = 8
        String state = LegacyBlockStates.toBlockState(XMaterial.SNOW, (byte) -1);
        assertEquals("minecraft:snow[layers=8]", state);
    }

    @Test
    void slabWithNegativeByteTopPosition() {
        // (byte) -8 → 0xF8 unsigned → bit 3 is set → top
        String state = LegacyBlockStates.toBlockState(XMaterial.STONE_SLAB, (byte) -8);
        assertEquals("minecraft:stone_slab[type=top]", state);
    }
}

package ru.soscreate.igrok;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BlockKnowledgeTest {

    @Test
    void mapsOverworldAndNetherWoodToMatchingPlanks() {
        assertEquals(Material.OAK_PLANKS, BlockKnowledge.planksForLog(Material.OAK_LOG));
        assertEquals(Material.SPRUCE_PLANKS, BlockKnowledge.planksForLog(Material.STRIPPED_SPRUCE_LOG));
        assertEquals(Material.CRIMSON_PLANKS, BlockKnowledge.planksForLog(Material.CRIMSON_STEM));
        assertEquals(Material.CRIMSON_PLANKS, BlockKnowledge.planksForLog(Material.CRIMSON_HYPHAE));
        assertEquals(Material.BAMBOO_PLANKS, BlockKnowledge.planksForLog(Material.BAMBOO_BLOCK));
        assertNull(BlockKnowledge.planksForLog(Material.IRON_INGOT));
    }

    @Test
    void recognizesRussianNamesForBlocksAndMaterials() {
        assertEquals(Material.PISTON, BlockKnowledge.findMaterialMention("что делает поршень"));
        assertEquals(Material.IRON_ORE, BlockKnowledge.findMaterialMention("где найти железную руду"));
        assertEquals(Material.REDSTONE_BLOCK, BlockKnowledge.findMaterialMention("рецепт блока редстоуна"));
        assertEquals(Material.BARREL, BlockKnowledge.findMaterialMention("для чего нужна бочка"));
    }

    @Test
    void describesMinimumToolTiersForOres() {
        assertTrue(BlockKnowledge.quickAction(Material.COAL_ORE).contains("любой киркой"));
        assertTrue(BlockKnowledge.quickAction(Material.IRON_ORE).contains("каменная кирка"));
        assertTrue(BlockKnowledge.quickAction(Material.DIAMOND_ORE).contains("железная кирка"));
        assertTrue(BlockKnowledge.quickAction(Material.REDSTONE_ORE).contains("железная кирка"));
        assertTrue(BlockKnowledge.quickAction(Material.ANCIENT_DEBRIS).contains("алмазная кирка"));
        assertTrue(BlockKnowledge.quickAction(Material.NETHER_GOLD_ORE).contains("любой киркой"));
    }

    @Test
    void distinguishesSupportedInteractionsFromRecognizedOnlyBlocks() {
        assertEquals("хранение", BlockKnowledge.purpose(Material.BARREL));
        assertEquals("рабочая станция", BlockKnowledge.purpose(Material.CRAFTING_TABLE));
        assertEquals("автоматика", BlockKnowledge.purpose(Material.REDSTONE_BLOCK));
        assertTrue(BlockKnowledge.quickAction(Material.CRAFTING_TABLE).contains("серверным рецептам"));
        assertTrue(BlockKnowledge.quickAction(Material.BREWING_STAND).contains("пока не реализована"));
        assertTrue(BlockKnowledge.quickAction(Material.COMPOSTER).contains("пока не реализованы"));
    }
}

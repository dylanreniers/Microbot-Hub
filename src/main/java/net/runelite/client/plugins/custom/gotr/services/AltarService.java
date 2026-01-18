package net.runelite.client.plugins.custom.gotr.services;

import net.runelite.api.DynamicObject;
import net.runelite.api.GameObject;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.TileObject;
import net.runelite.client.plugins.custom.gotr.GotrConfig;
import net.runelite.client.plugins.custom.gotr.GotrConstants;
import net.runelite.client.plugins.custom.gotr.GotrScript;
import net.runelite.client.plugins.custom.gotr.data.GuardianPortalInfo;
import net.runelite.client.plugins.custom.gotr.data.Mode;
import net.runelite.client.plugins.custom.gotr.data.RuneType;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.Microbot.log;

/**
 * Service for handling altar and portal operations in GOTR
 */
@Singleton
public class AltarService {

    private final LocationService locationService;
    private final PouchService pouchService;
    private GotrConfig config;

    @Inject
    public AltarService(LocationService locationService, PouchService pouchService) {
        this.locationService = locationService;
        this.pouchService = pouchService;
    }

    public void setConfig(GotrConfig config) {
        this.config = config;
    }

    /**
     * Enters the best available altar based on configuration
     * @return true if entering an altar
     */
    public boolean enterBestAvailableAltar() {
        GameObject availableAltar = getAvailableAltars().stream().findFirst().orElse(null);

        if (availableAltar != null && !Rs2Player.isMoving()) {
            log("Entering altar with ID: " + availableAltar.getId());
            Rs2GameObject.interact(availableAltar);

            Global.sleepUntil(() ->
                !locationService.isInMainRegion() ||
                !Objects.equals(getAvailableAltars().stream().findFirst().orElse(null), availableAltar),
                5000
            );
            Global.sleep(Rs2Random.randomGaussian(1000, 300));
            return true;
        }

        return false;
    }

    /**
     * Crafts runes at the current altar
     * @return true if crafting operation was performed
     */
    public boolean craftRunes() {
        if (locationService.isInMainRegion()) {
            return false;
        }

        TileObject rcAltar = findRunecraftingAltar();
        if (rcAltar == null) {
            return false;
        }

        if (Rs2Player.isMoving()) {
            return true;
        }

        pouchService.emptyPouchesIfNeeded();

        if (Rs2Inventory.hasItem(GotrConstants.GUARDIAN_ESSENCE)) {
            Rs2GameObject.interact(rcAltar.getId());
            log("Crafting runes on altar: " + rcAltar.getId());
            Global.sleep(Rs2Random.randomGaussian(Rs2Random.between(1000, 1500), 300));
        } else if (!Rs2Player.isMoving()) {
            return leaveAltar();
        }

        return true;
    }

    /**
     * Leaves the current altar and returns to main region
     * @return true if leaving altar
     */
    public boolean leaveAltar() {
        TileObject rcPortal = findPortalToLeaveAltar();
        if (rcPortal != null && Rs2GameObject.interact(rcPortal.getId())) {
            log("Leaving the altar...");
            Global.sleepUntilTrue(() -> locationService.isInMainRegion(), 100, 10000);
            Global.sleep(Rs2Random.randomGaussian(750, 150));
            return true;
        }
        return false;
    }

    /**
     * Gets list of available altars based on configuration and requirements
     */
    public List<GameObject> getAvailableAltars() {
        List<GameObject> availableAltars = Rs2GameObject.getGameObjects().stream()
            .filter(this::isValidPortal)
            .collect(Collectors.toList());

        log("Found " + availableAltars.size() + " available altars after filtering.");

        if (config == null) {
            return availableAltars;
        }

        return sortAltarsByMode(availableAltars);
    }

    /**
     * Finds a runecrafting altar in the current area
     */
    public TileObject findRunecraftingAltar() {
        return Rs2GameObject.findObject(GotrConstants.RC_ALTAR_IDS);
    }

    /**
     * Finds a portal to leave the current altar
     */
    public TileObject findPortalToLeaveAltar() {
        return Rs2GameObject.findObject(GotrConstants.RC_PORTAL_IDS);
    }

    private boolean isValidPortal(GameObject gameObject) {
        if (!GotrScript.guardianPortalInfo.containsKey(gameObject.getId())) {
            return false;
        }

        GuardianPortalInfo portalInfo = GotrScript.guardianPortalInfo.get(gameObject.getId());

        // Check level requirement
        if (portalInfo.getRequiredLevel() > Microbot.getClient().getBoostedSkillLevel(Skill.RUNECRAFT)) {
            log("Filtered altar " + portalInfo.getName() + " – insufficient RC level");
            return false;
        }

        // Check quest requirement
        if (portalInfo.getQuestState() != QuestState.FINISHED) {
            log("Filtered altar " + portalInfo.getName() + " – quest not complete");
            return false;
        }

        // Check if portal is active (has correct animation)
        if (!isPortalActive(gameObject)) {
            return false;
        }

        log("Adding " + portalInfo.getName() + " to list of available altars");
        return true;
    }

    private boolean isPortalActive(GameObject gameObject) {
        try {
            DynamicObject dynamicObject = (DynamicObject) gameObject.getRenderable();
            if (dynamicObject.getAnimation() == null) {
                return false;
            }
            return dynamicObject.getAnimation().getId() == GotrConstants.PORTAL_ANIMATION_ID;
        } catch (ClassCastException e) {
            return false;
        }
    }

    private List<GameObject> sortAltarsByMode(List<GameObject> availableAltars) {
        Mode mode = config.Mode();

        if (mode == Mode.POINTS) {
            return sortByStrongestCell(availableAltars);
        }

        if (mode == Mode.BALANCED && GotrScript.elementalRewardPoints < GotrScript.catalyticRewardPoints) {
            return availableAltars.stream()
                .sorted(GotrScript.elementalRewardPoints < GotrScript.catalyticRewardPoints
                    ? Comparator.comparingInt(TileObject::getId)
                    : Comparator.comparingInt(TileObject::getId).reversed())
                .collect(Collectors.toList());
        } else if (mode == Mode.CATALYTIC) {
            return availableAltars.stream()
                    .sorted(Comparator.comparingInt(TileObject::getId).reversed())
                    .collect(Collectors.toList());
        } else {
            return availableAltars.stream()
                    .sorted(Comparator.comparingInt(TileObject::getId))
                    .collect(Collectors.toList());
        }
    }

    private List<GameObject> sortByStrongestCell(List<GameObject> availableAltars) {
        int elementalPoints = GotrScript.elementalRewardPoints;
        int catalyticPoints = GotrScript.catalyticRewardPoints;

        log("Sorting by CellType (strongest→weakest) for POINTS mode...");

        return availableAltars.stream()
            .sorted(
                Comparator.<GameObject>comparingInt(
                    o -> GotrScript.guardianPortalInfo.get(o.getId()).getCellType().ordinal()
                ).reversed()
                .thenComparingInt(o -> {
                    RuneType rt = GotrScript.guardianPortalInfo.get(o.getId()).getRuneType();
                    boolean preferElemental = elementalPoints < catalyticPoints;
                    return ((preferElemental && rt == RuneType.ELEMENTAL) ||
                            (!preferElemental && rt == RuneType.CATALYTIC)) ? 0 : 1;
                })
            )
            .peek(o -> log("Altar " +
                GotrScript.guardianPortalInfo.get(o.getId()).getName() + " – " +
                GotrScript.guardianPortalInfo.get(o.getId()).getCellType()))
            .collect(Collectors.toList());
    }
}

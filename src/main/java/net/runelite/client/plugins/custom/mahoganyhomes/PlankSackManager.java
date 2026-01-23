package net.runelite.client.plugins.custom.mahoganyhomes;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.primitives.Ints;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemID;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Arrays;
import java.util.List;

/**
 * Manages plank sack state and operations
 */
@Slf4j
@Singleton
public class PlankSackManager {

    private static final List<Integer> PLANK_IDS = Arrays.asList(
        ItemID.PLANK,
        ItemID.OAK_PLANK,
        ItemID.TEAK_PLANK,
        ItemID.MAHOGANY_PLANK
    );

    public static final List<String> PLANK_NAMES = Arrays.asList(
        "Plank",
        "Oak plank",
        "Teak plank",
        "Mahogany plank"
    );

    private final Client client;

    @Getter
    private int plankCount = -1;

    private Multiset<Integer> inventorySnapshot;
    private boolean checkForUpdate = false;

    @Inject
    public PlankSackManager(Client client) {
        this.client = client;
    }

    public void setPlankCount(int count) {
        this.plankCount = Ints.constrainToRange(count, 0, 28);
        log.info("New plank count: {}", this.plankCount);
    }

    public void markForUpdate(Multiset<Integer> snapshot) {
        this.inventorySnapshot = snapshot;
        this.checkForUpdate = true;
    }

    public void processInventoryChange(ItemContainer itemContainer) {
        if (!checkForUpdate) {
            return;
        }

        checkForUpdate = false;
        Multiset<Integer> currentInventory = createSnapshot(itemContainer);

        if (inventorySnapshot != null) {
            updatePlankCountFromInventoryDiff(currentInventory, inventorySnapshot);
        }
    }

    public void processChatMessage(String message) {
        final String cleanMessage = Text.removeTags(message);

        log.info("Processing message...");
        if (cleanMessage.contains("planks:")) {
            log.info("Parsing");
            parsePlankCountFromMessage(cleanMessage);
        } else if (cleanMessage.equals("You haven't got any planks that can go in the sack.")) {
            checkForUpdate = false;
        } else if (cleanMessage.equals("Your sack is full.")) {
            setPlankCount(28);
            checkForUpdate = false;
        } else if (cleanMessage.equals("Your sack is empty.")) {
            setPlankCount(0);
            checkForUpdate = false;
        }
    }

    public void processPlankUsageFromSack(int planksUsed) {
        if (planksUsed > 0 && plankCount >= 0) {
            setPlankCount(plankCount - planksUsed);
        }
    }

    public Multiset<Integer> createInventorySnapshot() {
        ItemContainer container = client.getItemContainer(InventoryID.INVENTORY);
        return createSnapshot(container);
    }

    private void updatePlankCountFromInventoryDiff(Multiset<Integer> current, Multiset<Integer> previous) {
        current.entrySet().forEach(entry -> {
            int id = entry.getElement();
            int currentCount = entry.getCount();
            int previousCount = previous.count(id);
            int diff = currentCount - previousCount;

            if (diff != 0) {
                plankCount += diff;
            }
        });

        setPlankCount(plankCount);
    }

    private void parsePlankCountFromMessage(String message) {
        try {
            int totalPlanks = Arrays.stream(message.split(","))
                .mapToInt(s -> Integer.parseInt(s.split(":\u00A0")[1]))
                .sum();
            setPlankCount(totalPlanks);
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            log.warn("Failed to parse plank count from message: {}", message, e);
        }
    }

    private Multiset<Integer> createSnapshot(ItemContainer container) {
        if (container == null) {
            return HashMultiset.create();
        }

        Multiset<Integer> snapshot = HashMultiset.create();
        Arrays.stream(container.getItems())
            .filter(item -> PLANK_IDS.contains(item.getId()))
            .forEach(item -> snapshot.add(item.getId(), item.getQuantity()));

        return snapshot;
    }

    public boolean hasPlanks() {
        return plankCount > 0;
    }

    public boolean isFull() {
        return plankCount >= 28;
    }

    public boolean isEmpty() {
        return plankCount == 0;
    }
}

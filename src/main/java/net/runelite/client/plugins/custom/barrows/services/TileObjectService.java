package net.runelite.client.plugins.custom.barrows.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectCache;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;

import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
public class TileObjectService {

    private static final String STAIRCASE = "Staircase";
    private static final String CHEST = "Chest";
    private static final String POOL_OF_REFRESHMENT = "Pool of Refreshment";
    private static final String SARCOPHAGUS = "Sarcophagus";

    private final Rs2TileObjectCache rs2TileObjectCache;

    public Optional<Rs2TileObjectModel> getPoolOfRefreshmentObject() {
        return Optional.ofNullable(
                rs2TileObjectCache
                        .query()
                        .withName(POOL_OF_REFRESHMENT)
                        .nearestOnClientThread(60)
        );
    }

    public Optional<Rs2TileObjectModel> getStaircase() {
        return Optional.ofNullable(
                rs2TileObjectCache
                        .query()
                        .withName(STAIRCASE)
                        .nearestOnClientThread(20)
        );
    }

    public Optional<Rs2TileObjectModel> getChest() {
        return Optional.ofNullable(
                rs2TileObjectCache
                        .query()
                        .withName(CHEST)
                        .nearestOnClientThread(40)
        );
    }

    public Optional<Rs2TileObjectModel> getSarcophagus() {
        return Optional.ofNullable(
                rs2TileObjectCache
                        .query()
                        .withName(SARCOPHAGUS)
                        .nearestOnClientThread(40)
        );
    }
}

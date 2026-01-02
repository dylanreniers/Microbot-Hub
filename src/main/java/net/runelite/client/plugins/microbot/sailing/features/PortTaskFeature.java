package net.runelite.client.plugins.microbot.sailing.features;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ObjectID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectCache;
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectQueryable;
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel;
import net.runelite.client.plugins.microbot.sailing.SailingConfig;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.widget.Rs2Widget.findWidget;

@Slf4j
public class PortTaskFeature {

    private static final int PORT_TASK_GAME_OBJECT_ID = 60288;
    private static final int PORT_TASK_BOARD_WIDGET_ID = 61669379;
    private static final int ACCEPT_TASK_WIDGET_ID = 61734922;

    private State state = State.OPENING_NOTICE_BOARD;

    @Inject
    private Rs2TileObjectCache rs2TileObjectCache;

    private Rs2TileObjectModel noticeBoard;
    private boolean searchingForNoticeBoard;
    public void run(SailingConfig config) {
        if (state == State.OPENING_NOTICE_BOARD) {
            log.info("Looking for notice board");

            if (!searchingForNoticeBoard) {
                searchingForNoticeBoard = true;
                noticeBoard = new Rs2TileObjectQueryable()
                        //.fromWorldView()
                        .where(rs2TileObjectModel -> rs2TileObjectModel.getId() == PORT_TASK_GAME_OBJECT_ID)
                        .nearest(40);
                state = State.GETTING_TASK;
            }
        } else if (state == State.GETTING_TASK) {
            if (noticeBoard == null) {
                log.error("Notice board not found");
            } else {
                log.info("Found notice board");
                noticeBoard.click("inspect");

                Widget portSarimTaskWidget = findWidget("Port sarim", List.of(Rs2Widget.getWidget(PORT_TASK_BOARD_WIDGET_ID).getChildren()),  false);
                sleep(600, 2400);
                Rs2Widget.clickWidget(portSarimTaskWidget.getId());
                sleep(600, 1200);
                Widget mainWidget = Rs2Widget.getWidget(270, 0);
                Widget optionWidget = findWidget("Accept task", List.of(mainWidget), false);
                Rs2Widget.clickWidget(optionWidget);
                state = State.TAKING_CARGO;
            }
        }
    }

    public enum State {
        OPENING_NOTICE_BOARD,GETTING_TASK,TAKING_CARGO,SAILING_TO_DESTINATION,DELIVERING_CARGO;
    }

}

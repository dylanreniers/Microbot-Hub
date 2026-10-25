package net.runelite.client.plugins.custom.customvaletotems.utils;

import net.runelite.api.ItemID;
import net.runelite.client.plugins.custom.customvaletotems.CustomValeTotemConfig;

/**
 * Utility class to map configuration enums to RuneLite item IDs
 */
public class FletchingItemMapper {

    /**
     * Get the item ID for the configured log type
     * @param logType the configured log type
     * @return the corresponding ItemID
     */
    public static int getLogItemId(CustomValeTotemConfig.LogType logType) {
        switch (logType) {
            case OAK:
                return ItemID.OAK_LOGS;
            case WILLOW:
                return ItemID.WILLOW_LOGS;
            case MAPLE:
                return ItemID.MAPLE_LOGS;
            case YEW:
                return ItemID.YEW_LOGS;
            case MAGIC:
                return ItemID.MAGIC_LOGS;
            default:
                return ItemID.YEW_LOGS; // fallback
        }
    }

    /**
     * Get the item ID for the configured bow type
     * @param logType the configured log type
     * @param bowType the configured bow type
     * @return the corresponding unstrung bow ItemID
     */
    public static int getBowItemId(CustomValeTotemConfig.LogType logType, CustomValeTotemConfig.BowType bowType) {
        if (bowType == CustomValeTotemConfig.BowType.SHORTBOW) {
            switch (logType) {
                case OAK:
                    return ItemID.OAK_SHORTBOW_U;
                case WILLOW:
                    return ItemID.WILLOW_SHORTBOW_U;
                case MAPLE:
                    return ItemID.MAPLE_SHORTBOW_U;
                case YEW:
                    return ItemID.YEW_SHORTBOW_U;
                case MAGIC:
                    return ItemID.MAGIC_SHORTBOW_U;
                default:
                    return ItemID.YEW_SHORTBOW_U; // fallback
            }
        } else { // LONGBOW
            switch (logType) {
                case OAK:
                    return ItemID.OAK_LONGBOW_U;
                case WILLOW:
                    return ItemID.WILLOW_LONGBOW_U;
                case MAPLE:
                    return ItemID.MAPLE_LONGBOW_U;
                case YEW:
                    return ItemID.YEW_LONGBOW_U;
                case MAGIC:
                    return ItemID.MAGIC_LONGBOW_U;
                default:
                    return ItemID.YEW_LONGBOW_U; // fallback
            }
        }
    }

    /**
     * Get the item ID for the configured strung (finished) bow type
     * @param logType the configured log type
     * @param bowType the configured bow type
     * @return the corresponding strung bow ItemID
     */
    public static int getStrungBowItemId(CustomValeTotemConfig.LogType logType, CustomValeTotemConfig.BowType bowType) {
        if (bowType == CustomValeTotemConfig.BowType.SHORTBOW) {
            switch (logType) {
                case OAK:
                    return ItemID.OAK_SHORTBOW;
                case WILLOW:
                    return ItemID.WILLOW_SHORTBOW;
                case MAPLE:
                    return ItemID.MAPLE_SHORTBOW;
                case YEW:
                    return ItemID.YEW_SHORTBOW;
                case MAGIC:
                    return ItemID.MAGIC_SHORTBOW;
                default:
                    return ItemID.YEW_SHORTBOW; // fallback
            }
        } else { // LONGBOW
            switch (logType) {
                case OAK:
                    return ItemID.OAK_LONGBOW;
                case WILLOW:
                    return ItemID.WILLOW_LONGBOW;
                case MAPLE:
                    return ItemID.MAPLE_LONGBOW;
                case YEW:
                    return ItemID.YEW_LONGBOW;
                case MAGIC:
                    return ItemID.MAGIC_LONGBOW;
                default:
                    return ItemID.YEW_LONGBOW; // fallback
            }
        }
    }

    /**
     * Get the item ID the totem is decorated with (and that is counted/withdrawn)
     * for the selected material source.
     * <ul>
     *   <li>LOGS / UNSTRUNG_BOWS → unstrung bow (fletched or withdrawn ready-made)</li>
     *   <li>STRUNG_BOWS → finished strung bow</li>
     * </ul>
     * @param logType the configured log type
     * @param bowType the configured bow type
     * @param materialMode the configured material source
     * @return the corresponding bow ItemID used for decoration
     */
    public static int getDecorationItemId(CustomValeTotemConfig.LogType logType,
                                          CustomValeTotemConfig.BowType bowType,
                                          CustomValeTotemConfig.MaterialMode materialMode) {
        if (materialMode == CustomValeTotemConfig.MaterialMode.STRUNG_BOWS) {
            return getStrungBowItemId(logType, bowType);
        }
        return getBowItemId(logType, bowType);
    }

    /**
     * Get the fletching interface child ID for the specified bow type
     * Updated to provide more accurate child IDs based on the actual fletching interface layout
     * @param logType the configured log type
     * @param bowType the configured bow type
     * @return the child ID for the fletching interface
     */
    public static int getFletchingInterfaceChildId(CustomValeTotemConfig.LogType logType, CustomValeTotemConfig.BowType bowType) {
        // The fletching interface typically shows multiple options based on log type
        // For most log types, shortbow (u) and longbow (u) are the primary options
        
        if (bowType == CustomValeTotemConfig.BowType.SHORTBOW) {
            switch (logType) {
                case OAK:
                    return 15; // Oak shortbow (u) - typically second option for oak
                case WILLOW:
                    return 15; // Willow shortbow (u) - typically second option for willow
                case MAPLE:
                    return 15; // Maple shortbow (u) - typically second option for maple
                case YEW:
                    return 15; // Yew shortbow (u) - typically second option for yew
                case MAGIC:
                    return 15; // Magic shortbow (u) - typically second option for magic
                default:
                    return 15; // fallback to shortbow option
            }
        } else { // LONGBOW
            switch (logType) {
                case OAK:
                    return 16; // Oak longbow (u) - typically third option for oak
                case WILLOW:
                    return 16; // Willow longbow (u) - typically third option for willow
                case MAPLE:
                    return 16; // Maple longbow (u) - typically third option for maple
                case YEW:
                    return 16; // Yew longbow (u) - typically third option for yew
                case MAGIC:
                    return 16; // Magic longbow (u) - typically third option for magic
                default:
                    return 16; // fallback to longbow option
            }
        }
    }

    /**
     * Get the expected keyboard shortcut for the specified bow type
     * This helps with debugging and logging which key should be pressed
     * @param logType the configured log type
     * @param bowType the configured bow type
     * @return the expected keyboard shortcut (e.g., "2" for shortbow, "3" for longbow)
     */
    public static String getExpectedKeyboardShortcut(CustomValeTotemConfig.LogType logType, CustomValeTotemConfig.BowType bowType) {
        // Based on standard fletching interface layout:
        // Shortbow (u) is typically option 2 (key '2')
        // Longbow (u) is typically option 3 (key '3')
        
        if (bowType == CustomValeTotemConfig.BowType.SHORTBOW) {
            return "2"; // Expected shortcut for shortbow
        } else {
            return "3"; // Expected shortcut for longbow
        }
    }

    /**
     * Get a human-readable description of the configured fletching setup
     * @param logType the configured log type
     * @param bowType the configured bow type
     * @return formatted description string
     */
    public static String getFletchingDescription(CustomValeTotemConfig.LogType logType, CustomValeTotemConfig.BowType bowType) {
        return String.format("%s %s (u)", logType.getDisplayName().replace(" Logs", ""), bowType.getDisplayName());
    }

    /**
     * Get a detailed description including expected keyboard shortcut
     * @param logType the configured log type
     * @param bowType the configured bow type
     * @return formatted description string with keyboard shortcut info
     */
    public static String getFletchingDescriptionWithShortcut(CustomValeTotemConfig.LogType logType, CustomValeTotemConfig.BowType bowType) {
        String baseDescription = getFletchingDescription(logType, bowType);
        String expectedKey = getExpectedKeyboardShortcut(logType, bowType);
        return String.format("%s (expected key: %s)", baseDescription, expectedKey);
    }
} 
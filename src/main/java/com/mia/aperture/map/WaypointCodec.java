package com.mia.aperture.map;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WaypointCodec {
    public static final String PREFIX = "[MIA:WP]";
    private static final Pattern P = Pattern.compile(
            "\\[MIA:WP\\]\\s+\"(.+?)\"\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+([A-Za-z]+)");

    private WaypointCodec() {}

    public static String encode(Waypoint w) {
        return PREFIX + " \"" + w.name.replace("\"", "'") + "\" "
                + w.x + " " + w.y + " " + w.z + " " + w.color.name().toLowerCase();
    }

    public static Optional<Waypoint> decode(String text) {
        if (text == null) return Optional.empty();
        Matcher m = P.matcher(text);
        if (!m.find()) return Optional.empty();
        try {
            WaypointColor color = WaypointColor.valueOf(m.group(5).toUpperCase());
            return Optional.of(new Waypoint(m.group(1),
                    Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)), color));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

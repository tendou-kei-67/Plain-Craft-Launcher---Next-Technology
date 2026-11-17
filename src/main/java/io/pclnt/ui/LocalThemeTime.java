package io.pclnt.ui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.stream.Collectors;

final class LocalThemeTime {
    private static final Set<String> SOUTHERN = loadSouthern();
    private static final LocalTime EARLY_DAY = LocalTime.of(6, 0), EARLY_NIGHT = LocalTime.of(18, 30);
    private static final LocalTime LATE_DAY = LocalTime.of(6, 30), LATE_NIGHT = LocalTime.of(19, 30);

    private LocalThemeTime() {}

    static boolean isDark(ZonedDateTime now) {
        var rules = now.getZone().getRules();
        var transition = rules.nextTransition(now.toInstant());
        boolean seasonalClock = rules.isDaylightSavings(now.toInstant()) || !rules.getTransitionRules().isEmpty()
                || transition != null && transition.getInstant().isBefore(now.plusYears(1).toInstant())
                && rules.isDaylightSavings(transition.getInstant());
        int month = now.getMonthValue();
        boolean summer = SOUTHERN.contains(now.getZone().getId()) ? month == 12 || month <= 2 : month >= 6 && month <= 8;
        boolean extended = seasonalClock || summer;
        return now.toLocalTime().isBefore(extended ? EARLY_DAY : LATE_DAY)
                || !now.toLocalTime().isBefore(extended ? LATE_NIGHT : EARLY_NIGHT);
    }

    private static Set<String> loadSouthern() {
        try (var stream = LocalThemeTime.class.getResourceAsStream("southern-zones.txt")) {
            if (stream == null) return Set.of();
            try (var lines = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                return lines.lines().filter(line -> !line.isBlank() && !line.startsWith("#"))
                        .collect(Collectors.toUnmodifiableSet());
            }
        } catch (IOException exception) {
            return Set.of();
        }
    }
}

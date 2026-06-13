package net.informaticalibera.streakapp;

import com.codename1.io.JSONParser;
import com.codename1.io.JSONWriter;
import com.codename1.io.Log;
import com.codename1.io.Preferences;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists user-defined goals and dated configuration revisions.
 */
final class GoalStore {
    static final String TYPE_APP = "app";
    static final String TYPE_MANUAL = "manual";

    private static final String PREF_CUSTOM_GOALS = "goals.custom.v1";
    private static final String PREF_REVISION_INDEX = "goals.revision.index.v1";
    private static final String PREF_REVISION_PREFIX = "goals.revision.v1.";
    private static final String PREF_MANUAL_DONE_PREFIX = "goals.manual.done.";
    private static final String PREF_APP_AVAILABLE_PREFIX = "app.available.";

    private final List<Goal> defaults = new ArrayList<Goal>();

    GoalStore() {
        defaults.add(Goal.app("AnkiDroid", "com.ichi2.anki", 3, false));
        defaults.add(Goal.app("Duolingo", "com.duolingo", 3, false));
        defaults.add(Goal.app("Drops", "com.languagedrops.drops.international", 3, false));
        defaults.add(Goal.app("Rosetta Stone", "air.com.rosettastone.mobile.CoursePlayer", 3, false));
        defaults.add(Goal.app("Talkpal", "ai.talkpal", 3, false));
        defaults.add(Goal.app("Quizlet", "com.quizlet.quizletandroid", 3, false));
    }

    List<Goal> allGoals() {
        List<Goal> result = new ArrayList<Goal>();
        for (int i = 0; i < defaults.size(); i++) {
            Goal goal = defaults.get(i);
            if (isAvailable(goal)) {
                result.add(goal);
            }
        }
        result.addAll(loadCustomGoals());
        return result;
    }

    List<Goal> activeGoals() {
        List<Goal> result = new ArrayList<Goal>();
        List<Goal> goals = allGoals();
        for (int i = 0; i < goals.size(); i++) {
            Goal goal = goals.get(i);
            if (isEnabled(goal)) {
                result.add(goal.withMinutes(targetMinutes(goal)));
            }
        }
        return result;
    }

    List<Goal> goalsForDay(long dayStart) {
        List<Long> revisions = loadRevisionIndex();
        long selected = selectRevision(revisions, dayStart);
        if (selected == Long.MIN_VALUE) {
            return activeGoals();
        }
        String json = Preferences.get(PREF_REVISION_PREFIX + selected, "");
        List<Goal> parsed = parseGoals(json);
        return parsed == null ? activeGoals() : parsed;
    }

    static long selectRevision(List<Long> revisions, long dayStart) {
        long selected = Long.MIN_VALUE;
        for (int i = 0; i < revisions.size(); i++) {
            long revision = revisions.get(i).longValue();
            if (revision <= dayStart && revision > selected) {
                selected = revision;
            }
        }
        return selected;
    }

    void ensureHistory(long effectiveDayStart) {
        if (loadRevisionIndex().isEmpty()) {
            recordConfiguration(effectiveDayStart);
        }
    }

    void recordConfiguration(long effectiveDayStart) {
        Preferences.set(PREF_REVISION_PREFIX + effectiveDayStart, serializeGoals(activeGoals()));
        List<Long> revisions = loadRevisionIndex();
        boolean found = false;
        for (int i = 0; i < revisions.size(); i++) {
            if (revisions.get(i).longValue() == effectiveDayStart) {
                found = true;
                break;
            }
        }
        if (!found) {
            revisions.add(Long.valueOf(effectiveDayStart));
            Collections.sort(revisions);
            saveRevisionIndex(revisions);
        }
    }

    void clearConfigurationHistory() {
        List<Long> revisions = loadRevisionIndex();
        for (int i = 0; i < revisions.size(); i++) {
            Preferences.delete(PREF_REVISION_PREFIX + revisions.get(i).longValue());
        }
        Preferences.delete(PREF_REVISION_INDEX);
    }

    boolean reconcileInstalledApps(List<InstalledApp> installedApps) {
        boolean changed = false;
        for (int i = 0; i < defaults.size(); i++) {
            Goal goal = defaults.get(i);
            boolean installed = containsInstalledPackage(installedApps, goal.packageName);
            if (isAvailable(goal) != installed) {
                changed = true;
            }
            Preferences.set(availabilityKey(goal), installed);
        }

        List<Goal> custom = loadCustomGoals();
        for (int i = custom.size() - 1; i >= 0; i--) {
            Goal goal = custom.get(i);
            if (goal.isApp() && !containsInstalledPackage(installedApps, goal.packageName)) {
                custom.remove(i);
                Preferences.delete(enabledKey(goal));
                Preferences.delete(minutesKey(goal));
                changed = true;
            }
        }
        if (changed) {
            saveCustomGoals(custom);
        }
        return changed;
    }

    boolean isEnabled(Goal goal) {
        return Preferences.get(enabledKey(goal), true);
    }

    void setEnabled(Goal goal, boolean enabled) {
        Preferences.set(enabledKey(goal), enabled);
    }

    int targetMinutes(Goal goal) {
        if (!goal.isApp()) {
            return 0;
        }
        return Preferences.get(minutesKey(goal), goal.defaultMinutes);
    }

    void setTargetMinutes(Goal goal, int minutes) {
        if (goal.isApp()) {
            Preferences.set(minutesKey(goal), minutes);
        }
    }

    boolean containsApp(String packageName) {
        List<Goal> goals = allGoals();
        for (int i = 0; i < goals.size(); i++) {
            Goal goal = goals.get(i);
            if (goal.isApp() && goal.packageName.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    boolean containsName(String name) {
        List<Goal> goals = allGoals();
        for (int i = 0; i < goals.size(); i++) {
            if (goals.get(i).name.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    Goal addApp(String name, String packageName, int defaultMinutes) {
        if (containsApp(packageName)) {
            return null;
        }
        Goal goal = Goal.app(name, packageName, defaultMinutes, true);
        List<Goal> custom = loadCustomGoals();
        custom.add(goal);
        saveCustomGoals(custom);
        setEnabled(goal, true);
        return goal;
    }

    Goal addManual(String name) {
        String id = "manual." + System.currentTimeMillis();
        List<Goal> custom = loadCustomGoals();
        int suffix = 1;
        while (containsId(custom, id)) {
            id = "manual." + System.currentTimeMillis() + "." + suffix++;
        }
        Goal goal = Goal.manual(id, name, true);
        custom.add(goal);
        saveCustomGoals(custom);
        setEnabled(goal, true);
        return goal;
    }

    void remove(Goal goal) {
        if (!goal.removable) {
            return;
        }
        List<Goal> custom = loadCustomGoals();
        for (int i = custom.size() - 1; i >= 0; i--) {
            if (custom.get(i).id.equals(goal.id)) {
                custom.remove(i);
            }
        }
        saveCustomGoals(custom);
        Preferences.delete(enabledKey(goal));
        if (goal.isApp()) {
            Preferences.delete(minutesKey(goal));
        }
    }

    boolean isManualDone(Goal goal, String dayId) {
        return goal.isManual() && Preferences.get(manualDoneKey(goal, dayId), false);
    }

    void setManualDone(Goal goal, String dayId, boolean done) {
        if (goal.isManual()) {
            Preferences.set(manualDoneKey(goal, dayId), done);
        }
    }

    void clearManualCompletions(String dayId, List<Goal> goals) {
        for (int i = 0; i < goals.size(); i++) {
            Goal goal = goals.get(i);
            if (goal.isManual()) {
                Preferences.delete(manualDoneKey(goal, dayId));
            }
        }
    }

    List<InstalledApp> parseInstalledApps(String json) {
        List<InstalledApp> result = new ArrayList<InstalledApp>();
        if (json == null || json.length() == 0) {
            return result;
        }
        try {
            Map<String, Object> root = JSONParser.parseJSON(json);
            Object rawApps = root.get("apps");
            if (!(rawApps instanceof List)) {
                return result;
            }
            List<?> apps = (List<?>)rawApps;
            for (int i = 0; i < apps.size(); i++) {
                Object raw = apps.get(i);
                if (!(raw instanceof Map)) {
                    continue;
                }
                Map<?, ?> map = (Map<?, ?>)raw;
                String packageName = stringValue(map.get("package"));
                String name = stringValue(map.get("name"));
                if (packageName.length() > 0 && name.length() > 0) {
                    result.add(new InstalledApp(name, packageName));
                }
            }
        } catch (IOException ex) {
            Log.e(ex);
        }
        Collections.sort(result, new Comparator<InstalledApp>() {
            @Override
            public int compare(InstalledApp left, InstalledApp right) {
                int byName = left.name.compareToIgnoreCase(right.name);
                return byName != 0 ? byName : left.packageName.compareTo(right.packageName);
            }
        });
        return result;
    }

    static String serializeGoals(List<Goal> goals) {
        Map<String, Object> root = new HashMap<String, Object>();
        List<Object> encoded = new ArrayList<Object>();
        for (int i = 0; i < goals.size(); i++) {
            Goal goal = goals.get(i);
            Map<String, Object> item = new HashMap<String, Object>();
            item.put("id", goal.id);
            item.put("name", goal.name);
            item.put("type", goal.type);
            item.put("package", goal.packageName);
            item.put("minutes", Integer.valueOf(goal.defaultMinutes));
            item.put("removable", Boolean.valueOf(goal.removable));
            encoded.add(item);
        }
        root.put("goals", encoded);
        return JSONWriter.toJson(root);
    }

    static List<Goal> parseGoals(String json) {
        if (json == null || json.length() == 0) {
            return null;
        }
        try {
            Map<String, Object> root = JSONParser.parseJSON(json);
            Object rawGoals = root.get("goals");
            if (!(rawGoals instanceof List)) {
                return null;
            }
            List<Goal> result = new ArrayList<Goal>();
            List<?> goals = (List<?>)rawGoals;
            for (int i = 0; i < goals.size(); i++) {
                Object raw = goals.get(i);
                if (!(raw instanceof Map)) {
                    continue;
                }
                Map<?, ?> map = (Map<?, ?>)raw;
                String id = stringValue(map.get("id"));
                String name = stringValue(map.get("name"));
                String type = stringValue(map.get("type"));
                String packageName = stringValue(map.get("package"));
                int minutes = intValue(map.get("minutes"), 0);
                boolean removable = booleanValue(map.get("removable"), true);
                if (id.length() > 0 && name.length() > 0
                        && (TYPE_APP.equals(type) || TYPE_MANUAL.equals(type))) {
                    result.add(new Goal(id, name, type, packageName, minutes, removable));
                }
            }
            return result;
        } catch (IOException ex) {
            Log.e(ex);
            return null;
        }
    }

    private List<Goal> loadCustomGoals() {
        List<Goal> result = parseGoals(Preferences.get(PREF_CUSTOM_GOALS, ""));
        return result == null ? new ArrayList<Goal>() : result;
    }

    private void saveCustomGoals(List<Goal> goals) {
        Preferences.set(PREF_CUSTOM_GOALS, serializeGoals(goals));
    }

    private List<Long> loadRevisionIndex() {
        List<Long> result = new ArrayList<Long>();
        String raw = Preferences.get(PREF_REVISION_INDEX, "");
        if (raw.length() == 0) {
            return result;
        }
        String[] values = raw.split(",");
        for (int i = 0; i < values.length; i++) {
            try {
                result.add(Long.valueOf(Long.parseLong(values[i])));
            } catch (NumberFormatException ignored) {
            }
        }
        Collections.sort(result);
        return result;
    }

    private void saveRevisionIndex(List<Long> revisions) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < revisions.size(); i++) {
            if (i > 0) {
                value.append(',');
            }
            value.append(revisions.get(i).longValue());
        }
        Preferences.set(PREF_REVISION_INDEX, value.toString());
    }

    private boolean containsId(List<Goal> goals, String id) {
        for (int i = 0; i < goals.size(); i++) {
            if (goals.get(i).id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsInstalledPackage(List<InstalledApp> apps, String packageName) {
        for (int i = 0; i < apps.size(); i++) {
            if (packageName.equals(apps.get(i).packageName)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAvailable(Goal goal) {
        return !goal.isApp() || Preferences.get(availabilityKey(goal), true);
    }

    private String availabilityKey(Goal goal) {
        return PREF_APP_AVAILABLE_PREFIX + goal.packageName;
    }

    private String enabledKey(Goal goal) {
        if (goal.isApp()) {
            return "app.enabled." + goal.packageName;
        }
        return "goal.enabled." + goal.id;
    }

    private String minutesKey(Goal goal) {
        return "app.minutes." + goal.packageName;
    }

    private String manualDoneKey(Goal goal, String dayId) {
        return PREF_MANUAL_DONE_PREFIX + goal.id + "." + dayId;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number)value).intValue();
        }
        try {
            return Integer.parseInt(stringValue(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean) {
            return ((Boolean)value).booleanValue();
        }
        if ("true".equalsIgnoreCase(stringValue(value))) {
            return true;
        }
        if ("false".equalsIgnoreCase(stringValue(value))) {
            return false;
        }
        return fallback;
    }

    static final class Goal {
        final String id;
        final String name;
        final String type;
        final String packageName;
        final int defaultMinutes;
        final boolean removable;

        private Goal(String id, String name, String type, String packageName,
                int defaultMinutes, boolean removable) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.packageName = packageName == null ? "" : packageName;
            this.defaultMinutes = defaultMinutes;
            this.removable = removable;
        }

        static Goal app(String name, String packageName, int minutes, boolean removable) {
            return new Goal("app." + packageName, name, TYPE_APP, packageName, minutes, removable);
        }

        static Goal manual(String id, String name, boolean removable) {
            return new Goal(id, name, TYPE_MANUAL, "", 0, removable);
        }

        Goal withMinutes(int minutes) {
            return new Goal(id, name, type, packageName, minutes, removable);
        }

        boolean isApp() {
            return TYPE_APP.equals(type);
        }

        boolean isManual() {
            return TYPE_MANUAL.equals(type);
        }
    }

    static final class InstalledApp {
        final String name;
        final String packageName;

        InstalledApp(String name, String packageName) {
            this.name = name;
            this.packageName = packageName;
        }
    }
}

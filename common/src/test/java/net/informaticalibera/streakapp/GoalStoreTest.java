package net.informaticalibera.streakapp;

import com.codename1.testing.AbstractTest;
import java.util.ArrayList;
import java.util.List;
import net.informaticalibera.streakapp.GoalStore.Goal;
import net.informaticalibera.streakapp.GoalStore.InstalledApp;

public class GoalStoreTest extends AbstractTest {
    @Override
    public boolean shouldExecuteOnEDT() {
        return false;
    }

    @Override
    public boolean runTest() {
        List<Goal> goals = new ArrayList<Goal>();
        goals.add(Goal.app("Example", "com.example.app", 12, true)
                .withSettings(12, "Review chapters 4-6\nThen revise vocabulary."));
        goals.add(Goal.manual("manual.walk", "Walk", true));

        List<Goal> decoded = GoalStore.parseGoals(GoalStore.serializeGoals(goals));
        assertNotNull(decoded, "Serialized goals should be readable");
        assertEqual(2, decoded.size(), "Both goals should survive serialization");
        assertEqual("com.example.app", decoded.get(0).packageName, "App package should be preserved");
        assertEqual("Review chapters 4-6\nThen revise vocabulary.", decoded.get(0).note,
                "Multiline notes should survive serialization");
        assertTrue(decoded.get(1).isManual(), "Manual goal type should be preserved");

        List<Long> revisions = new ArrayList<Long>();
        revisions.add(Long.valueOf(100L));
        revisions.add(Long.valueOf(300L));
        revisions.add(Long.valueOf(200L));
        assertEqual(200L, GoalStore.selectRevision(revisions, 250L),
                "A day must use the latest configuration that was already effective");
        assertEqual(Long.MIN_VALUE, GoalStore.selectRevision(revisions, 50L),
                "A day before the first revision must not use a future configuration");

        GoalStore store = new GoalStore();
        List<InstalledApp> apps = store.parseInstalledApps(
                "{\"apps\":[{\"name\":\"Notes\",\"package\":\"com.example.notes\"}]}");
        assertEqual(1, apps.size(), "Installed-app JSON should be parsed");
        assertEqual("Notes", apps.get(0).name, "Installed-app label should be preserved");

        Goal custom = store.addApp("Temporary", "com.example.temporary", 5);
        assertNotNull(custom, "A custom app should be added before reconciliation");
        String longNote = "A note can be as long as needed. "
                + "This verifies that goal notes are not truncated by the model.";
        store.setNote(custom, longNote);
        assertEqual(longNote, store.note(custom), "Custom goal notes should be persisted");
        List<InstalledApp> installed = defaultInstalledApps();
        store.reconcileInstalledApps(installed);
        assertTrue(!store.containsApp("com.example.temporary"),
                "A custom app that is no longer installed should be removed");
        assertTrue(!store.containsApp("com.duolingo"),
                "A missing default app should disappear from settings");

        installed.add(new InstalledApp("Duolingo", "com.duolingo"));
        store.reconcileInstalledApps(installed);
        assertTrue(store.containsApp("com.duolingo"),
                "A reinstalled default app should become available again");

        assertTrue(!StreakApp.qualifiesAsCompletedDay(0, 1, 1),
                "Manual goals alone must not advance the streak");
        assertTrue(StreakApp.qualifiesAsCompletedDay(1, 2, 2),
                "A day with an app and all goals complete should advance the streak");
        assertEqual("https://quizlet.com/user/NIPPONITA-jp/sets",
                StreakApp.launchUrlForGoal(
                        Goal.app("Quizlet", "com.quizlet.quizletandroid", 3, false)),
                "Quizlet should open the requested sets page");

        restoreDefaultAvailability(store);
        return true;
    }

    private List<InstalledApp> defaultInstalledApps() {
        List<InstalledApp> installed = new ArrayList<InstalledApp>();
        installed.add(new InstalledApp("AnkiDroid", "com.ichi2.anki"));
        installed.add(new InstalledApp("Drops", "com.languagedrops.drops.international"));
        installed.add(new InstalledApp("Rosetta Stone", "air.com.rosettastone.mobile.CoursePlayer"));
        installed.add(new InstalledApp("Talkpal", "ai.talkpal"));
        installed.add(new InstalledApp("Quizlet", "com.quizlet.quizletandroid"));
        return installed;
    }

    private void restoreDefaultAvailability(GoalStore store) {
        List<InstalledApp> installed = defaultInstalledApps();
        installed.add(new InstalledApp("Duolingo", "com.duolingo"));
        store.reconcileInstalledApps(installed);
    }
}

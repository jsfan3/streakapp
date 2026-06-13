package net.informaticalibera.streakapp;

import static com.codename1.ui.CN.*;

import com.codename1.components.InfiniteProgress;
import com.codename1.components.SpanLabel;
import com.codename1.components.ToastBar;
import com.codename1.io.Log;
import com.codename1.io.Preferences;
import com.codename1.io.Util;
import com.codename1.l10n.L10NManager;
import com.codename1.system.Lifecycle;
import com.codename1.system.NativeLookup;
import com.codename1.ui.*;
import com.codename1.ui.events.FocusListener;
import com.codename1.ui.layouts.BorderLayout;
import com.codename1.ui.layouts.BoxLayout;
import com.codename1.ui.layouts.FlowLayout;
import com.codename1.ui.plaf.UIManager;
import com.codename1.ui.spinner.Picker;
import com.codename1.ui.util.UITimer;
import com.codename1.ui.util.Resources;
import java.util.Calendar;
import java.util.Date;
import java.util.Hashtable;
import java.util.List;
import net.informaticalibera.streakapp.native_.AndroidUsageBridge;
import net.informaticalibera.streakapp.GoalStore.Goal;
import net.informaticalibera.streakapp.GoalStore.InstalledApp;

/**
 * Codename One entry point for the study streak tracker.
 *
 * <p>The UI remains in common Java while Android-specific app launch and usage
 * statistics are isolated in {@link AndroidUsageBridge}.</p>
 */
public class StreakApp extends Lifecycle {
    private static final long MINUTE = 60_000L;
    private static final int DEFAULT_APP_MINUTES = 3;
    private static final int FREEZE_EVERY_COMPLETED_DAYS = 4;
    private static final int MAX_FREEZES = 4;

    private static final String PREF_STREAK = "streak.current";
    private static final String PREF_FREEZES = "streak.freezes";
    private static final String PREF_FREEZE_PROGRESS = "streak.freezeProgress";
    private static final String PREF_LAST_DAY_START = "streak.lastDayStart";
    private static final String PREF_THEME_MODE = "theme.mode";
    private static final String PREF_LANGUAGE_MODE = "language.mode";
    private static final String PREF_DAY_MODE = "day.mode";
    private static final String PREF_FONT_SCALE = "font.scale";
    private static final String PREF_INITIAL_STREAK_IMPORTED = "streak.initialImported";

    private static final String THEME_SYSTEM = "system";
    private static final String THEME_LIGHT = "light";
    private static final String THEME_DARK = "dark";
    private static final String LANGUAGE_SYSTEM = "system";
    private static final String LANGUAGE_ENGLISH = "en";
    private static final String LANGUAGE_ITALIAN = "it";
    private static final String DAY_CALENDAR = "calendar";
    private static final String DAY_STUDY = "study";
    private static final int STUDY_DAY_START_HOUR = 4;
    private static final int FONT_SCALE_STEP_PERCENT = 10;
    private static final int MIN_FONT_SCALE_PERCENT = 70;
    private static final int MAX_FONT_SCALE_PERCENT = 180;
    private static final int RESUME_STATS_SETTLE_MILLIS = 2000;
    private static final int RETURN_REFRESH_INTERVAL_MILLIS = 2000;
    private static final int RETURN_REFRESH_ATTEMPTS = 3;
    private static final int MIN_REFRESH_BUSY_MILLIS = 500;
    private static final int ANDROID_BACK_KEY = -23452;
    private static final String QUIZLET_PACKAGE = "com.quizlet.quizletandroid";
    private static final String QUIZLET_SETS_URL = "https://quizlet.com/user/NIPPONITA-jp/sets";

    private final GoalStore goalStore = new GoalStore();
    private AndroidUsageBridge usageBridge;
    private Form homeForm;
    private Form settingsForm;
    private DayProgress lastProgress;
    private UITimer returnRefreshTimer;
    private Component refreshCommandComponent;
    private int returnRefreshAttemptsRemaining;
    private boolean booted;
    private volatile boolean refreshInProgress;

    @Override
    public void init(Object context) {
        applyDarkModePreference();
        super.init(context);
        installLocalizationBundle();
        applyFontScalePreference();
    }

    @Override
    public void start() {
        super.start();
        Form current = CN.getCurrentForm();
        if (booted && (current == homeForm || homeForm == null)) {
            refreshHomeAfterResume();
        } else if (booted && current == settingsForm) {
            refreshSettingsAfterResume();
        }
    }

    @Override
    public void runApp() {
        usageBridge = (AndroidUsageBridge)NativeLookup.create(AndroidUsageBridge.class);
        booted = true;
        refreshHomeAsync(false);
    }

    private void installLocalizationBundle() {
        String language = Preferences.get(PREF_LANGUAGE_MODE, LANGUAGE_SYSTEM);
        if (LANGUAGE_SYSTEM.equals(language)) {
            language = L10NManager.getInstance().getLanguage();
        }
        Resources global = Resources.getGlobalResources();
        Hashtable<String, String> bundle = global == null ? null : global.getL10N("messages", language);
        if (bundle == null && global != null) {
            bundle = global.getL10N("messages", "");
        }
        if (bundle != null) {
            UIManager.getInstance().setBundle(bundle);
        }
    }

    private void applyThemePreference() {
        applyDarkModePreference();
        UIManager.initFirstTheme(getThemeName());
        installLocalizationBundle();
        applyFontScalePreference();
    }

    private void applyDarkModePreference() {
        String mode = Preferences.get(PREF_THEME_MODE, THEME_SYSTEM);
        if (THEME_LIGHT.equals(mode)) {
            Display.getInstance().setDarkMode(Boolean.FALSE);
        } else if (THEME_DARK.equals(mode)) {
            Display.getInstance().setDarkMode(Boolean.TRUE);
        } else {
            Display.getInstance().setDarkMode(null);
        }
    }

    private void applyFontScalePreference() {
        int percent = currentFontScalePercent();
        if (percent != 100) {
            UIManager.getInstance().zoomFonts(percent / 100f);
        }
    }

    private void setFontScalePercent(int requestedPercent, Label valueLabel) {
        int currentPercent = currentFontScalePercent();
        int nextPercent = Math.max(MIN_FONT_SCALE_PERCENT, Math.min(MAX_FONT_SCALE_PERCENT, requestedPercent));
        if (nextPercent == currentPercent) {
            updateFontScaleValue(valueLabel, nextPercent);
            return;
        }
        Form currentForm = CN.getCurrentForm();
        Preferences.set(PREF_FONT_SCALE, nextPercent / 100f);
        updateFontScaleValue(valueLabel, nextPercent);
        if (currentForm != null) {
            AutoShrinkSupport.prepareForThemeRefresh(currentForm);
        }
        UIManager.getInstance().zoomFonts(nextPercent / (float) currentPercent);
        refreshFormAfterThemeChange(currentForm);
    }

    private int currentFontScalePercent() {
        return Math.round(Preferences.get(PREF_FONT_SCALE, 1f) * 100f);
    }

    private void refreshFormAfterThemeChange(Form form) {
        if (form == null) {
            return;
        }
        AutoShrinkSupport.refreshTitleComponent(form);
        form.refreshTheme();
        form.setShouldCalcPreferredSize(true);
        AutoShrinkSupport.resetAndApply(form);
        form.revalidate();
        form.revalidateLater();
        form.repaint();
        AutoShrinkSupport.resetAndApplyLater(form);
    }

    private void refreshHomeAsync(boolean showToast) {
        refreshHomeAsync(showToast, 0);
    }

    private void refreshHomeAsync(boolean showToast, int settleDelayMillis) {
        if (refreshInProgress) {
            return;
        }
        refreshInProgress = true;
        long refreshStarted = System.currentTimeMillis();
        setRefreshBusy(true);
        if (homeForm == null) {
            showLoadingHome();
        }
        Dialog refreshDialog = createRefreshDialog();
        refreshDialog.showPacked(BorderLayout.CENTER, false);

        startThread(() -> {
            try {
                if (settleDelayMillis > 0) {
                    Util.sleep(settleDelayMillis);
                }
                DayProgress progress = computeTodayProgress();
                sleepUntilMinimumBusyTime(refreshStarted);
                callSerially(() -> {
                    completeRefresh(refreshDialog, () -> {
                        lastProgress = progress;
                        showHome(progress, showToast);
                    });
                });
            } catch (Throwable t) {
                Log.e(t);
                sleepUntilMinimumBusyTime(refreshStarted);
                callSerially(() -> {
                    completeRefresh(refreshDialog, () ->
                            ToastBar.showErrorMessage(text("toast.refresh.failed", "Refresh failed")));
                });
            }
        }, "StreakApp Refresh").start();
    }

    private Dialog createRefreshDialog() {
        Dialog dialog = new Dialog(text("refresh.dialog.title", "Updating"));
        dialog.setDisposeWhenPointerOutOfBounds(false);
        dialog.setLayout(new BorderLayout());

        Container body = new Container(BoxLayout.y());
        body.add(FlowLayout.encloseCenter(new InfiniteProgress()));
        body.add(span(text("refresh.dialog.body", "Updating statistics..."), "DialogBody"));
        dialog.add(BorderLayout.CENTER, body);
        return dialog;
    }

    private void completeRefresh(Dialog refreshDialog, Runnable afterDialogClosed) {
        refreshInProgress = false;
        if (!Display.isInitialized()) {
            return;
        }
        setRefreshBusy(false);
        refreshDialog.dispose();
        callSerially(afterDialogClosed);
    }

    private void sleepUntilMinimumBusyTime(long refreshStarted) {
        long elapsed = System.currentTimeMillis() - refreshStarted;
        if (elapsed < MIN_REFRESH_BUSY_MILLIS) {
            Util.sleep(MIN_REFRESH_BUSY_MILLIS - (int) elapsed);
        }
    }

    private void refreshHomeAfterResume() {
        refreshHomeAsync(false, RESUME_STATS_SETTLE_MILLIS);
    }

    private DayProgress computeTodayProgress() {
        long todayStart = startOfToday();
        long lastDayStart = Preferences.get(PREF_LAST_DAY_START, 0L);
        goalStore.ensureHistory(lastDayStart > 0L && lastDayStart <= todayStart
                ? lastDayStart : todayStart);
        synchronizeInstalledGoals(todayStart);
        DayProgress progress = loadProgress(todayStart, System.currentTimeMillis());
        if (canEvaluate(progress)) {
            reconcileStreak(progress);
            return loadProgress(todayStart, System.currentTimeMillis());
        }
        if (lastDayStart == 0L) {
            Preferences.set(PREF_LAST_DAY_START, todayStart);
        }
        return progress;
    }

    private void showLoadingHome() {
        showLoadingHome(false);
    }

    private void showLoadingHome(boolean back) {
        homeForm = new Form(text("app.title", "Streak"), new BorderLayout());
        homeForm.setScrollVisible(false);
        decorateToolbar(homeForm);
        Container content = createScreenContent();
        content.add(infoPanel(text("loading.title", "Loading"), text("loading.body", "Reading study app usage...")));
        homeForm.add(BorderLayout.CENTER, content);
        showForm(homeForm, back);
    }

    private void showHome(DayProgress progress, boolean showToast) {
        showHome(progress, showToast, false);
    }

    private void showHome(DayProgress progress, boolean showToast, boolean back) {
        homeForm = new Form(text("app.title", "Streak"), new BorderLayout());
        homeForm.setScrollVisible(false);
        decorateToolbar(homeForm);

        Container content = createScreenContent();
        content.add(createHero(progress));
        if (progress.enabledAppCount > 0 && !progress.usageAccessGranted) {
            content.add(createUsageAccessPanel());
        }
        content.add(sectionTitle(text("section.dailyGoals", "Daily goals")));
        if (progress.enabledAppCount == 0) {
            content.add(infoPanel(
                    text("empty.countedApps.title", "No counted apps"),
                    text("empty.countedApps.body",
                            "At least one active app goal is required to advance the streak.")));
        }
        for (int i = 0; i < progress.goals.length; i++) {
            if (progress.goals[i].enabled) {
                content.add(createGoalCard(progress.goals[i]));
            }
        }
        if (progress.enabledCount == 0) {
            content.add(infoPanel(
                    text("empty.goals.title", "No active goals"),
                    text("empty.goals.body", "Open Settings from the menu and enable at least one goal.")));
        }

        homeForm.add(BorderLayout.CENTER, content);
        showForm(homeForm, back);
        if (showToast) {
            ToastBar.showInfoMessage(text("toast.refreshed", "Updated"));
        }
    }

    private Container createScreenContent() {
        Container content = new Container(BoxLayout.y());
        content.setScrollableY(true);
        content.setScrollVisible(false);
        content.setTensileDragEnabled(false);
        content.setAlwaysTensile(false);
        content.setUIID("ScreenContent");
        return content;
    }

    private void decorateToolbar(Form form) {
        Toolbar toolbar = form.getToolbar();
        toolbar.setTitleCentered(true);
        Command refresh = toolbar.addMaterialCommandToRightBar("", FontImage.MATERIAL_REFRESH, e -> refreshHomeAsync(true));
        refreshCommandComponent = toolbar.findCommandComponent(refresh);
        if (refreshCommandComponent != null) {
            refreshCommandComponent.setUIID("ToolbarIconButton");
        }
        addSideMenuCommand(form, text("menu.home", "Home"), FontImage.MATERIAL_HOME, () -> showCachedHome());
        addSideMenuCommand(form, text("menu.settings", "Settings"), FontImage.MATERIAL_SETTINGS, () -> showSettings());
        addSideMenuCommand(form, text("menu.usageAccess", "Usage access"), FontImage.MATERIAL_SECURITY, () -> explainAndOpenUsageSettings());
        addSideMenuCommand(form, text("menu.info", "Information"), FontImage.MATERIAL_INFO_OUTLINE, () -> showInfo());
    }

    private void addSideMenuCommand(Form form, String title, char icon, Runnable action) {
        Toolbar toolbar = form.getToolbar();
        Command command = toolbar.addMaterialCommandToSideMenu(title, icon, e -> action.run());
        AutoShrinkSupport.registerSideMenuCommand(form, toolbar.findCommandComponent(command));
    }

    private void setRefreshBusy(boolean busy) {
        if (refreshCommandComponent == null) {
            return;
        }
        refreshCommandComponent.setUIID(busy ? "ToolbarIconButtonActive" : "ToolbarIconButton");
        refreshCommandComponent.setEnabled(!busy);
        refreshCommandComponent.repaint();
    }

    private void showCachedHome() {
        if (lastProgress != null) {
            showHome(lastProgress, false);
        } else {
            refreshHomeAsync(false);
        }
    }

    private void showInfo() {
        Form info = createBackForm(text("info.title", "Information"));
        Container content = createScreenContent();
        content.add(sectionTitle(text("info.aboutTitle", "About StreakApp")));
        content.add(span(text("info.body",
                "StreakApp is dedicated to the public domain under the CC0 1.0 Universal license. "
                        + "It is published on GitHub and developed by Francesco Galgani, jsfan3 on GitHub."),
                "BodyText"));
        Button website = button(text("info.website", "informatica-libera.net"), "LinkButton", FontImage.MATERIAL_LINK);
        website.addActionListener(e -> Display.getInstance().execute("https://www.informatica-libera.net/"));
        content.add(website);
        info.add(BorderLayout.CENTER, content);
        showForm(info, false);
    }

    private Container createHero(DayProgress progress) {
        Container hero = new Container(BoxLayout.y());
        hero.setUIID("HeroPanel");
        hero.add(label(text("hero.caption", "Current streak"), "HeroCaption"));
        hero.add(label(String.valueOf(Preferences.get(PREF_STREAK, 0)), "HeroValue"));
        hero.add(span(text("hero.freezeStatus", "Freezes available: {0}/{1}",
                Preferences.get(PREF_FREEZES, 0), MAX_FREEZES), "HeroMeta"));
        hero.add(span(text("hero.goalsStatus", "Goals completed today: {0}/{1}",
                progress.completeCount, progress.enabledCount), "HeroMeta"));
        hero.add(span(nextFreezeText(), "HeroMeta"));
        return hero;
    }

    private Container createUsageAccessPanel() {
        Container panel = new Container(BoxLayout.y());
        panel.setUIID("WarningPanel");
        panel.add(label(text("usageAccess.missing.title", "Usage access is off"), "WarningTitle"));
        panel.add(span(text("usageAccess.missing.body", "Enable it in Android Settings to measure time in external apps."), "WarningText"));
        Button settings = button(text("usageAccess.openSettings", "Open settings"), "SecondaryButton", FontImage.MATERIAL_SECURITY);
        settings.addActionListener(e -> explainAndOpenUsageSettings());
        panel.add(settings);
        return panel;
    }

    private Container createGoalCard(GoalProgress progress) {
        Container card = new Container(new BorderLayout());
        card.setUIID("StudyCard");

        Container details = new Container(BoxLayout.y());
        details.add(label(progress.goal.name, "AppName"));
        details.add(span(goalStatusText(progress), "AppMeta"));
        if (progress.goal.isApp()) {
            details.add(label(text("appCard.durationTarget", "{0} / {1} min",
                    formatDuration(progress.foregroundMillis), progress.targetMinutes),
                    progress.complete ? "GoodText" : "MutedText"));
        }

        Button action;
        if (progress.goal.isManual()) {
            action = button(progress.complete
                    ? text("button.done", "Done")
                    : text("button.markDone", "Mark done"),
                    "SmallButton", FontImage.MATERIAL_DONE);
            action.setEnabled(!progress.complete);
            action.addActionListener(e -> markManualGoalDone(progress.goal));
        } else {
            action = button(text("button.open", "Open"), "SmallButton", FontImage.MATERIAL_OPEN_IN_NEW);
            action.setEnabled(progress.installed);
            action.addActionListener(e -> launchApp(progress));
        }

        card.add(BorderLayout.CENTER, details);
        card.add(BorderLayout.EAST, FlowLayout.encloseCenter(action));
        return card;
    }

    private String goalStatusText(GoalProgress progress) {
        if (progress.goal.isManual()) {
            return progress.complete
                    ? text("manualStatus.complete", "Completed today")
                    : text("manualStatus.pending", "To do today");
        }
        if (!progress.installed) {
            return text("appStatus.notInstalled", "Not installed or not visible to Android");
        }
        if (!progress.usageAccessGranted) {
            return text("appStatus.waitingPermission", "Ready to open; tracking waits for permission");
        }
        if (progress.complete) {
            return text("appStatus.complete", "Daily target completed");
        }
        long missingMillis = progress.targetMinutes * MINUTE - progress.foregroundMillis;
        return text("appStatus.missing", "About {0} remaining", formatDuration(Math.max(0, missingMillis)));
    }

    private void launchApp(GoalProgress progress) {
        if (!progress.installed) {
            Dialog.show(text("dialog.appNotFound.title", "App not found"),
                    text("dialog.appNotFound.body", "I cannot open {0}.", progress.goal.name),
                    text("button.ok", "OK"), null);
            return;
        }
        if (!progress.usageAccessGranted
                && Dialog.show(text("usageAccess.title", "Usage access"),
                text("usageAccess.openWithoutTracking", "You can open the app, but StreakApp cannot count time until Usage Access is enabled."),
                text("button.settings", "Settings"), text("button.openAnyway", "Open anyway"))) {
            explainAndOpenUsageSettings();
            return;
        }
        String launchUrl = launchUrlForGoal(progress.goal);
        if (launchUrl.length() > 0) {
            Display.getInstance().execute(launchUrl);
            scheduleReturnRefreshes();
            return;
        }
        if (!isNativeReady() || !usageBridge.launchPackage(progress.goal.packageName)) {
            Dialog.show(text("dialog.launchFailed.title", "Launch failed"),
                    text("dialog.launchFailed.body", "Android did not open {0}.", progress.goal.name),
                    text("button.ok", "OK"), null);
            return;
        }
        scheduleReturnRefreshes();
    }

    private void markManualGoalDone(Goal goal) {
        goalStore.setManualDone(goal, dayId(startOfToday()), true);
        refreshHomeAsync(false);
    }

    private void scheduleReturnRefreshes() {
        if (returnRefreshTimer != null) {
            returnRefreshTimer.cancel();
        }
        returnRefreshAttemptsRemaining = RETURN_REFRESH_ATTEMPTS;
        returnRefreshTimer = UITimer.timer(RETURN_REFRESH_INTERVAL_MILLIS, true, homeForm, () -> {
            if (Display.getInstance().isMinimized() || CN.getCurrentForm() != homeForm) {
                return;
            }
            refreshHomeAsync(false);
            returnRefreshAttemptsRemaining--;
            if (returnRefreshAttemptsRemaining <= 0 && returnRefreshTimer != null) {
                returnRefreshTimer.cancel();
                returnRefreshTimer = null;
            }
        });
    }

    void showSettings() {
        settingsForm = createBackForm(text("settings.title", "Settings"));
        settingsForm.setName("settingsForm");
        Container content = createScreenContent();
        content.add(sectionTitle(text("settings.appearance", "Appearance")));
        content.add(createThemePickerRow());
        content.add(createLanguagePickerRow());
        content.add(createFontScaleRow());
        content.add(sectionTitle(text("settings.daySection", "Day boundary")));
        content.add(span(text("settings.dayMode.body", "Calendar days end at midnight. Study days end at 4:00 AM."), "BodyText"));
        content.add(createDayModePickerRow());
        content.add(sectionTitle(text("settings.dailyTargets", "Daily targets")));
        content.add(span(text("settings.dailyTargets.body",
                "Choose the goals that count toward the streak. App goals also have a minimum duration."),
                "BodyText"));

        List<Goal> goals = goalStore.allGoals();
        for (int i = 0; i < goals.size(); i++) {
            content.add(createGoalSettingsRow(goals.get(i)));
        }

        Button addApp = button(text("button.addApp", "Add app"), "PrimaryButton", FontImage.MATERIAL_ADD);
        addApp.setName("addAppButton");
        addApp.addActionListener(e -> showInstalledAppPicker());
        Button addManual = button(text("button.addActivity", "Add activity"), "SecondaryButton", FontImage.MATERIAL_ADD_TASK);
        addManual.setName("addManualButton");
        addManual.addActionListener(e -> addManualGoal());
        content.add(addApp);
        content.add(addManual);

        content.add(infoPanel(text("settings.freezes.title", "Streak freezes"),
                text("settings.freezes.body", "Every {0} completed days earns one freeze. The maximum available is {1}.",
                        FREEZE_EVERY_COMPLETED_DAYS, MAX_FREEZES)));

        Button reset = button(text("button.resetStreak", "Reset streak"), "DangerButton", FontImage.MATERIAL_RESTORE);
        reset.addActionListener(e -> resetStreakData());

        content.add(reset);
        if (!Preferences.get(PREF_INITIAL_STREAK_IMPORTED, false)) {
            Button importStreak = button(text("button.importStreak", "Set initial streak"),
                    "SecondaryButton", FontImage.MATERIAL_EDIT);
            importStreak.setName("importStreakButton");
            importStreak.addActionListener(e -> importInitialStreak());
            content.add(importStreak);
        }
        settingsForm.add(BorderLayout.CENTER, content);
        showForm(settingsForm, false);
    }

    private Container createGoalSettingsRow(Goal goal) {
        Container row = new Container(new BorderLayout());
        row.setUIID("SettingsRow");
        row.setName("goalRow." + goal.id);

        CheckBox enabled = new CheckBox(goal.name);
        enabled.setName("goalEnabled." + goal.id);
        enabled.setUIID("SettingsCheckBox");
        enabled.getAllStyles().setBgTransparency(0);
        enabled.setSelected(goalStore.isEnabled(goal));
        enabled.addActionListener(e -> {
            goalStore.setEnabled(goal, enabled.isSelected());
            recordTodayConfiguration();
            lastProgress = null;
        });

        Container controls = new Container(BoxLayout.x());
        controls.getAllStyles().setBgTransparency(0);
        if (goal.isApp()) {
            TextField minutes = new TextField(String.valueOf(goalStore.targetMinutes(goal)),
                    text("settings.minutesHint", "min"), 3, TextArea.NUMERIC);
            minutes.setUIID("SettingsField");
            minutes.addDataChangedListener((type, index) -> saveMinutesIfValid(goal, minutes, false));
            minutes.setDoneListener(e -> saveMinutesIfValid(goal, minutes, true));
            minutes.addFocusListener(new FocusListener() {
                @Override
                public void focusGained(com.codename1.ui.Component cmp) {
                }

                @Override
                public void focusLost(com.codename1.ui.Component cmp) {
                    saveMinutesIfValid(goal, minutes, true);
                }
            });
            controls.add(minutes);
        }
        if (goal.removable) {
            Button remove = button("", "DeleteButton", FontImage.MATERIAL_DELETE_OUTLINE);
            remove.addActionListener(e -> removeGoal(goal));
            controls.add(remove);
        }
        row.add(BorderLayout.CENTER, enabled);
        if (controls.getComponentCount() > 0) {
            row.add(BorderLayout.EAST, controls);
        }
        return row;
    }

    private void showInstalledAppPicker() {
        if (!isNativeReady()) {
            Dialog.show(text("dialog.notAvailable.title", "Not available"),
                    text("dialog.notAvailable.body", "This feature is available on Android devices."),
                    text("button.ok", "OK"), null);
            return;
        }
        Form pickerForm = createBackForm(text("appsPicker.title", "Add an app"), () -> showSettings(),
                text("button.back", "Back"));
        Container content = createScreenContent();
        TextField search = new TextField("", text("appsPicker.search", "Search apps"), 40, TextArea.ANY);
        search.setUIID("SettingsField");
        Container results = new Container(BoxLayout.y());
        results.getAllStyles().setBgTransparency(0);
        results.add(infoPanel(text("loading.title", "Loading"),
                text("appsPicker.loading", "Reading the apps installed on this phone...")));
        content.add(search);
        content.add(results);
        pickerForm.add(BorderLayout.CENTER, content);
        showForm(pickerForm, false);

        startThread(() -> {
            List<InstalledApp> apps = loadInstalledApps();
            callSerially(() -> {
                populateInstalledApps(results, apps, "");
                search.addDataChangedListener((type, index) ->
                        populateInstalledApps(results, apps, search.getText()));
            });
        }, "Installed Apps").start();
    }

    private List<InstalledApp> loadInstalledApps() {
        try {
            return goalStore.parseInstalledApps(usageBridge.listLaunchableApps());
        } catch (Throwable t) {
            Log.e(t);
            return goalStore.parseInstalledApps("");
        }
    }

    private boolean synchronizeInstalledGoals(long effectiveDayStart) {
        if (!isNativeReady()) {
            return false;
        }
        List<InstalledApp> installedApps = loadInstalledApps();
        if (installedApps.isEmpty()) {
            return false;
        }
        boolean changed = goalStore.reconcileInstalledApps(installedApps);
        if (changed) {
            goalStore.recordConfiguration(effectiveDayStart);
            lastProgress = null;
        }
        return changed;
    }

    private void refreshSettingsAfterResume() {
        startThread(() -> {
            boolean changed = synchronizeInstalledGoals(startOfToday());
            if (changed) {
                callSerially(() -> showSettings());
            }
        }, "Installed Apps Refresh").start();
    }

    private void populateInstalledApps(Container results, List<InstalledApp> apps, String query) {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase();
        results.removeAll();
        int visible = 0;
        for (int i = 0; i < apps.size(); i++) {
            InstalledApp app = apps.get(i);
            if (goalStore.containsApp(app.packageName)) {
                continue;
            }
            String searchable = (app.name + " " + app.packageName).toLowerCase();
            if (normalizedQuery.length() > 0 && searchable.indexOf(normalizedQuery) < 0) {
                continue;
            }
            Container row = new Container(new BorderLayout());
            row.setUIID("SettingsRow");
            Container details = new Container(BoxLayout.y());
            details.add(label(app.name, "SettingsLabel"));
            details.add(label(app.packageName, "AppMeta"));
            Button add = button(text("button.add", "Add"), "SmallButton", FontImage.MATERIAL_ADD);
            add.addActionListener(e -> {
                Goal added = goalStore.addApp(app.name, app.packageName, DEFAULT_APP_MINUTES);
                if (added != null) {
                    recordTodayConfiguration();
                    lastProgress = null;
                    ToastBar.showInfoMessage(text("toast.goalAdded", "Goal added"));
                    showSettings();
                }
            });
            row.add(BorderLayout.CENTER, details);
            row.add(BorderLayout.EAST, FlowLayout.encloseCenter(add));
            results.add(row);
            visible++;
        }
        if (visible == 0) {
            results.add(infoPanel(text("appsPicker.empty.title", "No apps found"),
                    text("appsPicker.empty.body", "Try a different search or add a manual activity.")));
        }
        results.revalidate();
        results.repaint();
    }

    private void addManualGoal() {
        TextField name = new TextField("", text("manualGoal.nameHint", "Activity name"), 80, TextArea.ANY);
        name.setUIID("SettingsField");
        Container body = new Container(BoxLayout.y());
        body.add(span(text("manualGoal.body", "Add an activity that you will mark as done manually."),
                "DialogBody"));
        body.add(name);
        Command cancel = new Command(text("button.cancel", "Cancel"));
        Command add = new Command(text("button.add", "Add"));
        if (Dialog.show(text("manualGoal.title", "New activity"), body, cancel, add) != add) {
            return;
        }
        String value = name.getText() == null ? "" : name.getText().trim();
        if (value.length() == 0 || value.length() > 80) {
            Dialog.show(text("dialog.invalidValue.title", "Invalid value"),
                    text("manualGoal.invalid", "Enter a name between 1 and 80 characters."),
                    text("button.ok", "OK"), null);
            return;
        }
        if (goalStore.containsName(value)) {
            Dialog.show(text("dialog.invalidValue.title", "Invalid value"),
                    text("manualGoal.duplicate", "A goal with this name already exists."),
                    text("button.ok", "OK"), null);
            return;
        }
        goalStore.addManual(value);
        recordTodayConfiguration();
        lastProgress = null;
        ToastBar.showInfoMessage(text("toast.goalAdded", "Goal added"));
        showSettings();
    }

    private void removeGoal(Goal goal) {
        if (!Dialog.show(text("removeGoal.title", "Remove goal"),
                text("removeGoal.body", "Remove {0} from the list?", goal.name),
                text("button.remove", "Remove"), text("button.cancel", "Cancel"))) {
            return;
        }
        goalStore.remove(goal);
        recordTodayConfiguration();
        lastProgress = null;
        ToastBar.showInfoMessage(text("toast.goalRemoved", "Goal removed"));
        showSettings();
    }

    private void importInitialStreak() {
        TextField value = new TextField("", text("importStreak.hint", "Current streak"), 6, TextArea.NUMERIC);
        value.setUIID("SettingsField");
        Container body = new Container(BoxLayout.y());
        body.add(span(text("importStreak.body",
                "Enter the streak accumulated before this installation. This can only be done once."),
                "DialogBody"));
        body.add(value);
        Command cancel = new Command(text("button.cancel", "Cancel"));
        Command confirm = new Command(text("button.confirm", "Confirm"));
        if (Dialog.show(text("importStreak.title", "Set initial streak"), body, cancel, confirm) != confirm) {
            return;
        }
        int streak;
        try {
            streak = Integer.parseInt(value.getText() == null ? "" : value.getText().trim());
        } catch (NumberFormatException ex) {
            streak = -1;
        }
        if (streak < 0 || streak > 999999) {
            Dialog.show(text("dialog.invalidValue.title", "Invalid value"),
                    text("importStreak.invalid", "Enter a number between 0 and 999999."),
                    text("button.ok", "OK"), null);
            return;
        }
        if (!Dialog.show(text("importStreak.title", "Set initial streak"),
                text("importStreak.confirm", "Set the current streak to {0}? This action cannot be repeated.", streak),
                text("button.confirm", "Confirm"), text("button.cancel", "Cancel"))) {
            return;
        }
        Preferences.set(PREF_STREAK, streak);
        Preferences.set(PREF_FREEZE_PROGRESS, streak % FREEZE_EVERY_COMPLETED_DAYS);
        Preferences.set(PREF_INITIAL_STREAK_IMPORTED, true);
        ToastBar.showInfoMessage(text("toast.streakImported", "Initial streak saved"));
        navigateHome();
    }

    private void recordTodayConfiguration() {
        goalStore.recordConfiguration(startOfToday());
    }

    private Form createBackForm(String title) {
        return createBackForm(title, () -> navigateHome(), text("menu.home", "Home"));
    }

    private Form createBackForm(String title, Runnable backAction, String backLabel) {
        Form form = new Form(title, new BorderLayout()) {
            @Override
            public void keyReleased(int keyCode) {
                // The Android port translates the system Back button before
                // CN1 dispatches it to the current form.
                if (keyCode == ANDROID_BACK_KEY) {
                    backAction.run();
                    return;
                }
                super.keyReleased(keyCode);
            }
        };
        form.setScrollVisible(false);
        form.setTensileDragEnabled(false);
        form.setAlwaysTensile(false);
        form.setMinimizeOnBack(false);
        Command back = form.getToolbar().setBackCommand(backLabel, e -> backAction.run());
        form.setBackCommand(back);
        return form;
    }

    private void showForm(Form form, boolean back) {
        form.setTensileDragEnabled(false);
        form.setAlwaysTensile(false);
        AutoShrinkSupport.install(form);
        if (back) {
            form.showBack();
        } else {
            form.show();
        }
        AutoShrinkSupport.refreshTitleComponent(form);
        form.revalidateLater();
        AutoShrinkSupport.resetAndApplyLater(form);
    }

    private Container createThemePickerRow() {
        Container row = new Container(new BorderLayout());
        row.setUIID("SettingsRow");
        row.add(BorderLayout.CENTER, label(text("settings.theme", "Theme"), "SettingsLabel"));

        Picker picker = new Picker();
        picker.setUIID("SettingsPicker");
        picker.setTraversable(true);
        picker.setUseLightweightPopup(true);
        picker.setType(Display.PICKER_TYPE_STRINGS);
        picker.setStrings(themeLabel(THEME_SYSTEM), themeLabel(THEME_LIGHT), themeLabel(THEME_DARK));
        picker.setSelectedString(themeLabel(Preferences.get(PREF_THEME_MODE, THEME_SYSTEM)));
        picker.addActionListener(e -> {
            Preferences.set(PREF_THEME_MODE, themeModeFromLabel(picker.getSelectedString()));
            applyThemePreference();
            showSettings();
        });
        row.add(BorderLayout.EAST, picker);
        return row;
    }

    private Container createLanguagePickerRow() {
        Container row = new Container(new BorderLayout());
        row.setUIID("SettingsRow");
        row.add(BorderLayout.CENTER, label(text("settings.language", "Language"), "SettingsLabel"));

        Picker picker = new Picker();
        picker.setUIID("SettingsPicker");
        picker.setTraversable(true);
        picker.setUseLightweightPopup(true);
        picker.setType(Display.PICKER_TYPE_STRINGS);
        picker.setStrings(languageLabel(LANGUAGE_SYSTEM), languageLabel(LANGUAGE_ENGLISH), languageLabel(LANGUAGE_ITALIAN));
        picker.setSelectedString(languageLabel(Preferences.get(PREF_LANGUAGE_MODE, LANGUAGE_SYSTEM)));
        picker.addActionListener(e -> {
            Preferences.set(PREF_LANGUAGE_MODE, languageModeFromLabel(picker.getSelectedString()));
            installLocalizationBundle();
            showSettings();
        });
        row.add(BorderLayout.EAST, picker);
        return row;
    }

    private Container createFontScaleRow() {
        Container row = new Container(BoxLayout.y());
        row.setUIID("SettingsRow");
        row.add(label(text("settings.textSize", "Text size"), "SettingsLabel"));

        Container controls = new Container(new FlowLayout(Component.CENTER));
        controls.setUIID("FontScaleControls");
        controls.getAllStyles().setBgTransparency(0);
        Label value = label(fontScaleText(), "SettingsValue");
        value.setAlignment(Component.CENTER);
        Button smaller = button("", "IconButton", FontImage.MATERIAL_TEXT_DECREASE);
        Button reset = button(text("button.default", "Default"), "SecondaryButton", FontImage.MATERIAL_RESTORE);
        Button larger = button("", "IconButton", FontImage.MATERIAL_TEXT_INCREASE);

        smaller.addActionListener(e -> {
            setFontScalePercent(currentFontScalePercent() - FONT_SCALE_STEP_PERCENT, value);
        });
        reset.addActionListener(e -> {
            setFontScalePercent(100, value);
        });
        larger.addActionListener(e -> {
            setFontScalePercent(currentFontScalePercent() + FONT_SCALE_STEP_PERCENT, value);
        });

        controls.add(smaller);
        controls.add(value);
        controls.add(larger);
        row.add(controls);
        row.add(reset);
        return row;
    }

    private void updateFontScaleValue(Label value, int percent) {
        if (value != null) {
            value.setText(fontScaleText(percent));
        }
    }

    private Container createDayModePickerRow() {
        Container row = new Container(new BorderLayout());
        row.setUIID("SettingsRow");
        row.add(BorderLayout.CENTER, label(text("settings.dayMode", "Day type"), "SettingsLabel"));

        Picker picker = new Picker();
        picker.setUIID("SettingsPicker");
        picker.setTraversable(true);
        picker.setUseLightweightPopup(true);
        picker.setType(Display.PICKER_TYPE_STRINGS);
        picker.setStrings(dayModeLabel(DAY_CALENDAR), dayModeLabel(DAY_STUDY));
        picker.setSelectedString(dayModeLabel(Preferences.get(PREF_DAY_MODE, DAY_CALENDAR)));
        picker.addActionListener(e -> {
            Preferences.set(PREF_DAY_MODE, dayModeFromLabel(picker.getSelectedString()));
            Preferences.set(PREF_LAST_DAY_START, startOfToday());
            recordTodayConfiguration();
            lastProgress = null;
        });
        row.add(BorderLayout.EAST, picker);
        return row;
    }

    private String themeLabel(String mode) {
        if (THEME_LIGHT.equals(mode)) {
            return text("theme.light", "Light");
        }
        if (THEME_DARK.equals(mode)) {
            return text("theme.dark", "Dark");
        }
        return text("theme.system", "System");
    }

    private String themeModeFromLabel(String label) {
        if (themeLabel(THEME_LIGHT).equals(label)) {
            return THEME_LIGHT;
        }
        if (themeLabel(THEME_DARK).equals(label)) {
            return THEME_DARK;
        }
        return THEME_SYSTEM;
    }

    private String languageLabel(String mode) {
        if (LANGUAGE_ENGLISH.equals(mode)) {
            return text("language.english", "English");
        }
        if (LANGUAGE_ITALIAN.equals(mode)) {
            return text("language.italian", "Italian");
        }
        return text("language.system", "System");
    }

    private String languageModeFromLabel(String label) {
        if (languageLabel(LANGUAGE_ENGLISH).equals(label)) {
            return LANGUAGE_ENGLISH;
        }
        if (languageLabel(LANGUAGE_ITALIAN).equals(label)) {
            return LANGUAGE_ITALIAN;
        }
        return LANGUAGE_SYSTEM;
    }

    private String dayModeLabel(String mode) {
        if (DAY_STUDY.equals(mode)) {
            return text("dayMode.study", "Study day");
        }
        return text("dayMode.calendar", "Calendar day");
    }

    private String dayModeFromLabel(String label) {
        if (dayModeLabel(DAY_STUDY).equals(label)) {
            return DAY_STUDY;
        }
        return DAY_CALENDAR;
    }

    private String fontScaleText() {
        return fontScaleText(currentFontScalePercent());
    }

    private String fontScaleText(int percent) {
        return text("settings.textSizeValue", "{0}%", percent);
    }

    private void navigateHome() {
        if (lastProgress != null) {
            showHome(lastProgress, false, true);
        } else {
            showLoadingHome(true);
        }
        refreshHomeAsync(false);
    }

    private boolean saveMinutesIfValid(Goal goal, TextField field, boolean showErrors) {
        String raw = field.getText();
        int minutes;
        try {
            minutes = Integer.parseInt(raw == null ? "" : raw.trim());
        } catch (NumberFormatException ex) {
            if (showErrors) {
                restoreMinutesField(goal, field, "dialog.invalidValue.body");
            }
            return false;
        }
        if (minutes < 1 || minutes > 240) {
            if (showErrors) {
                restoreMinutesField(goal, field, "dialog.invalidRange.body");
            }
            return false;
        }
        goalStore.setTargetMinutes(goal, minutes);
        recordTodayConfiguration();
        lastProgress = null;
        return true;
    }

    private void restoreMinutesField(Goal goal, TextField field, String messageKey) {
        String fallback = "dialog.invalidRange.body".equals(messageKey)
                ? "Minutes for {0} must be between 1 and 240."
                : "Check the minutes for {0}.";
        Dialog.show(text("dialog.invalidValue.title", "Invalid value"),
                text(messageKey, fallback, goal.name),
                text("button.ok", "OK"), null);
        field.setText(String.valueOf(goalStore.targetMinutes(goal)));
    }

    private void resetStreakData() {
        if (!Dialog.show(text("reset.title", "Reset streak"),
                text("reset.body", "Reset streak, freezes, and completed-day history? App settings will be kept."),
                text("button.reset", "Reset"), text("button.cancel", "Cancel"))) {
            return;
        }
        TextField confirmation = new TextField("", "RESET", 8, TextArea.ANY);
        confirmation.setUIID("SettingsField");
        Container body = new Container(BoxLayout.y());
        body.add(span(text("reset.confirmType", "Type RESET to confirm."), "DialogBody"));
        body.add(confirmation);
        Command cancel = new Command(text("button.cancel", "Cancel"));
        Command reset = new Command(text("button.reset", "Reset"));
        if (Dialog.show(text("reset.title", "Reset streak"), body, cancel, reset) != reset) {
            return;
        }
        if (!"RESET".equals(confirmation.getText())) {
            Dialog.show(text("dialog.invalidValue.title", "Invalid value"),
                    text("reset.confirmFailed", "The streak was not reset because the confirmation text did not match."),
                    text("button.ok", "OK"), null);
            return;
        }
        Preferences.set(PREF_STREAK, 0);
        Preferences.set(PREF_FREEZES, 0);
        Preferences.set(PREF_FREEZE_PROGRESS, 0);
        Preferences.set(PREF_LAST_DAY_START, startOfToday());
        for (int i = 0; i < 370; i++) {
            long day = addDays(startOfToday(), -i);
            goalStore.clearManualCompletions(dayId(day), goalStore.goalsForDay(day));
            Preferences.delete(dayCompleteKey(day));
            clearBeforeDayState(day);
        }
        goalStore.clearConfigurationHistory();
        recordTodayConfiguration();
        lastProgress = null;
        ToastBar.showInfoMessage(text("toast.streakReset", "Streak reset"));
        navigateHome();
    }

    private void explainAndOpenUsageSettings() {
        if (!isNativeReady()) {
            Dialog.show(text("dialog.notAvailable.title", "Not available"),
                    text("dialog.notAvailable.body", "This feature is available on Android devices."),
                    text("button.ok", "OK"), null);
            return;
        }
        if (Dialog.show(text("usageAccess.title", "Usage access"),
                text("usageAccess.instructions", "In Android Settings, find StreakApp, open it, enable Usage Access, then return."),
                text("button.open", "Open"), text("button.cancel", "Cancel"))) {
            usageBridge.openUsageAccessSettings();
        }
    }

    private DayProgress loadProgress(long fromMillis, long toMillis) {
        DayProgress day = new DayProgress();
        List<Goal> goals = goalStore.goalsForDay(fromMillis);
        day.goals = new GoalProgress[goals.size()];
        day.usageAccessGranted = isNativeReady() && usageBridge.isUsageAccessGranted();
        boolean historical = fromMillis < startOfToday();
        String progressDayId = dayId(fromMillis);
        for (int i = 0; i < goals.size(); i++) {
            Goal goal = goals.get(i);
            GoalProgress p = new GoalProgress();
            p.goal = goal;
            p.enabled = true;
            p.targetMinutes = goal.defaultMinutes;
            p.usageAccessGranted = day.usageAccessGranted;
            if (goal.isApp()) {
                day.enabledAppCount++;
                p.installed = historical || !isNativeReady() || safeIsLaunchable(goal.packageName);
                p.foregroundMillis = p.installed && day.usageAccessGranted
                        ? safeForegroundMillis(goal.packageName, fromMillis, toMillis)
                        : 0L;
                p.complete = p.installed && p.foregroundMillis >= p.targetMinutes * MINUTE;
            } else {
                p.installed = true;
                p.complete = goalStore.isManualDone(goal, progressDayId);
            }
            day.goals[i] = p;
            if (p.enabled) {
                day.enabledCount++;
                if (p.complete) {
                    day.completeCount++;
                }
            }
        }
        day.complete = qualifiesAsCompletedDay(
                day.enabledAppCount, day.enabledCount, day.completeCount);
        return day;
    }

    private boolean canEvaluate(DayProgress progress) {
        return progress.enabledAppCount > 0 && progress.usageAccessGranted;
    }

    /**
     * Rolls stale days forward before marking today's completed state.
     */
    private void reconcileStreak(DayProgress today) {
        long todayStart = startOfToday();
        long lastDay = Preferences.get(PREF_LAST_DAY_START, 0L);
        if (lastDay <= 0L || lastDay > todayStart) {
            Preferences.set(PREF_LAST_DAY_START, todayStart);
            lastDay = todayStart;
        }
        int guard = 0;
        long cursor = lastDay;
        while (cursor < todayStart && guard < 370) {
            DayProgress past = loadProgress(cursor, addDays(cursor, 1));
            if (past.enabledAppCount == 0) {
                cursor = addDays(cursor, 1);
                guard++;
                continue;
            }
            if (!canEvaluate(past)) {
                return;
            }
            finalizePastDay(cursor, past);
            cursor = addDays(cursor, 1);
            guard++;
        }
        Preferences.set(PREF_LAST_DAY_START, todayStart);
        if (today.complete) {
            markDayComplete(todayStart, true);
        } else {
            unmarkCurrentDayComplete(todayStart);
        }
    }

    private void finalizePastDay(long dayStart, DayProgress past) {
        if (Preferences.get(dayCompleteKey(dayStart), false)) {
            clearBeforeDayState(dayStart);
            return;
        }
        if (past.complete) {
            markDayComplete(dayStart, false);
            clearBeforeDayState(dayStart);
            return;
        }
        int freezes = Preferences.get(PREF_FREEZES, 0);
        if (freezes > 0) {
            Preferences.set(PREF_FREEZES, freezes - 1);
        } else {
            Preferences.set(PREF_STREAK, 0);
            Preferences.set(PREF_FREEZE_PROGRESS, 0);
        }
    }

    private void markDayComplete(long dayStart, boolean allowToast) {
        String completeKey = dayCompleteKey(dayStart);
        if (Preferences.get(completeKey, false)) {
            return;
        }
        if (allowToast) {
            Preferences.set(beforeDayExistsKey(dayStart), true);
            Preferences.set(beforeDayStreakKey(dayStart), Preferences.get(PREF_STREAK, 0));
            Preferences.set(beforeDayFreezesKey(dayStart), Preferences.get(PREF_FREEZES, 0));
            Preferences.set(beforeDayProgressKey(dayStart), Preferences.get(PREF_FREEZE_PROGRESS, 0));
        }
        Preferences.set(completeKey, true);
        Preferences.set(PREF_STREAK, Preferences.get(PREF_STREAK, 0) + 1);
        int progress = Preferences.get(PREF_FREEZE_PROGRESS, 0) + 1;
        if (progress >= FREEZE_EVERY_COMPLETED_DAYS) {
            int freezes = Preferences.get(PREF_FREEZES, 0);
            Preferences.set(PREF_FREEZES, Math.min(MAX_FREEZES, freezes + 1));
            progress = 0;
        }
        Preferences.set(PREF_FREEZE_PROGRESS, progress);
        if (allowToast) {
            ToastBar.showInfoMessage(text("toast.dayComplete", "Day completed"));
        }
    }

    private void unmarkCurrentDayComplete(long dayStart) {
        if (!Preferences.get(dayCompleteKey(dayStart), false)
                || !Preferences.get(beforeDayExistsKey(dayStart), false)) {
            return;
        }
        Preferences.set(PREF_STREAK, Preferences.get(beforeDayStreakKey(dayStart), 0));
        Preferences.set(PREF_FREEZES, Preferences.get(beforeDayFreezesKey(dayStart), 0));
        Preferences.set(PREF_FREEZE_PROGRESS, Preferences.get(beforeDayProgressKey(dayStart), 0));
        Preferences.delete(dayCompleteKey(dayStart));
        clearBeforeDayState(dayStart);
    }

    private void clearBeforeDayState(long dayStart) {
        Preferences.delete(beforeDayExistsKey(dayStart));
        Preferences.delete(beforeDayStreakKey(dayStart));
        Preferences.delete(beforeDayFreezesKey(dayStart));
        Preferences.delete(beforeDayProgressKey(dayStart));
    }

    private boolean safeIsLaunchable(String packageName) {
        try {
            return usageBridge.isPackageLaunchable(packageName);
        } catch (Throwable t) {
            Log.e(t);
            return false;
        }
    }

    private long safeForegroundMillis(String packageName, long fromMillis, long toMillis) {
        try {
            return Math.max(0L, usageBridge.getForegroundMillis(packageName, fromMillis, toMillis));
        } catch (Throwable t) {
            Log.e(t);
            return 0L;
        }
    }

    private boolean isNativeReady() {
        return usageBridge != null && usageBridge.isSupported();
    }

    static boolean qualifiesAsCompletedDay(
            int enabledAppCount, int enabledGoalCount, int completedGoalCount) {
        return enabledAppCount > 0
                && enabledGoalCount > 0
                && completedGoalCount == enabledGoalCount;
    }

    static String launchUrlForGoal(Goal goal) {
        return goal != null && QUIZLET_PACKAGE.equals(goal.packageName)
                ? QUIZLET_SETS_URL : "";
    }

    private String nextFreezeText() {
        int progress = Preferences.get(PREF_FREEZE_PROGRESS, 0);
        int remaining = FREEZE_EVERY_COMPLETED_DAYS - progress;
        if (Preferences.get(PREF_FREEZES, 0) >= MAX_FREEZES) {
            return text("freeze.maxed", "Freezes are full: use one before earning more.");
        }
        return text("freeze.next", "Next freeze in {0} completed days.", remaining);
    }

    private long startOfToday() {
        return startOfDay(System.currentTimeMillis());
    }

    private long startOfDay(long millis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(new Date(millis));
        int startHour = DAY_STUDY.equals(Preferences.get(PREF_DAY_MODE, DAY_CALENDAR)) ? STUDY_DAY_START_HOUR : 0;
        if (startHour > 0 && calendar.get(Calendar.HOUR_OF_DAY) < startHour) {
            calendar.add(Calendar.DATE, -1);
        }
        calendar.set(Calendar.HOUR_OF_DAY, startHour);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime().getTime();
    }

    private long addDays(long dayStart, int days) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(new Date(dayStart));
        calendar.add(Calendar.DATE, days);
        return calendar.getTime().getTime();
    }

    private String dayCompleteKey(long dayStart) {
        return "day.complete." + dayId(dayStart);
    }

    private String dayId(long dayStart) {
        Calendar c = Calendar.getInstance();
        c.setTime(new Date(dayStart));
        return c.get(Calendar.YEAR) + "." + (c.get(Calendar.MONTH) + 1) + "." + c.get(Calendar.DAY_OF_MONTH);
    }

    private String beforeDayExistsKey(long dayStart) {
        return "day.before.exists." + dayId(dayStart);
    }

    private String beforeDayStreakKey(long dayStart) {
        return "day.before.streak." + dayId(dayStart);
    }

    private String beforeDayFreezesKey(long dayStart) {
        return "day.before.freezes." + dayId(dayStart);
    }

    private String beforeDayProgressKey(long dayStart) {
        return "day.before.progress." + dayId(dayStart);
    }

    private String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, (millis + 999L) / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        if (minutes <= 0L) {
            return text("duration.seconds", "{0} sec", seconds);
        }
        if (seconds == 0L) {
            return text("duration.minutes", "{0} min", minutes);
        }
        return text("duration.minutesSeconds", "{0} min {1} sec", minutes, seconds);
    }

    private Container sectionTitle(String text) {
        Container wrapper = new Container(BoxLayout.y());
        wrapper.setUIID("SectionHeader");
        wrapper.add(label(text, "SectionTitle"));
        return wrapper;
    }

    private Container infoPanel(String title, String body) {
        Container panel = new Container(BoxLayout.y());
        panel.setUIID("InfoPanel");
        panel.add(label(title, "InfoTitle"));
        panel.add(span(body, "BodyText"));
        return panel;
    }

    private Label label(String text, String uiid) {
        Label label = new Label(text);
        label.setUIID(uiid);
        label.getAllStyles().setBgTransparency(0);
        return label;
    }

    private SpanLabel span(String text, String textUiid) {
        SpanLabel label = new SpanLabel(text);
        label.setTextUIID(textUiid);
        label.getAllStyles().setBgTransparency(0);
        label.getTextAllStyles().setBgTransparency(0);
        return label;
    }

    private Button button(String text, String uiid, char icon) {
        Button button = new Button(text);
        button.setUIID(uiid);
        FontImage.setMaterialIcon(button, icon, 3.8f);
        return button;
    }

    private String text(String key, String fallback, Object... args) {
        String value = UIManager.getInstance().localize(key, fallback);
        for (int i = 0; i < args.length; i++) {
            value = value.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return value;
    }

    private static final class DayProgress {
        GoalProgress[] goals;
        boolean usageAccessGranted;
        boolean complete;
        int enabledCount;
        int enabledAppCount;
        int completeCount;
    }

    private static final class GoalProgress {
        Goal goal;
        boolean enabled;
        boolean installed;
        boolean usageAccessGranted;
        boolean complete;
        int targetMinutes;
        long foregroundMillis;
    }
}

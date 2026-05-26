package net.informaticalibera.streakapp;

import static com.codename1.ui.CN.*;

import com.codename1.components.SpanLabel;
import com.codename1.components.ToastBar;
import com.codename1.io.Log;
import com.codename1.io.Preferences;
import com.codename1.io.Util;
import com.codename1.l10n.L10NManager;
import com.codename1.system.Lifecycle;
import com.codename1.system.NativeLookup;
import com.codename1.ui.Button;
import com.codename1.ui.CheckBox;
import com.codename1.ui.Command;
import com.codename1.ui.Component;
import com.codename1.ui.Container;
import com.codename1.ui.Dialog;
import com.codename1.ui.Display;
import com.codename1.ui.FontImage;
import com.codename1.ui.Form;
import com.codename1.ui.Label;
import com.codename1.ui.TextArea;
import com.codename1.ui.TextField;
import com.codename1.ui.Toolbar;
import com.codename1.ui.events.FocusListener;
import com.codename1.ui.layouts.BorderLayout;
import com.codename1.ui.layouts.BoxLayout;
import com.codename1.ui.layouts.FlowLayout;
import com.codename1.ui.plaf.Border;
import com.codename1.ui.plaf.Style;
import com.codename1.ui.plaf.UIManager;
import com.codename1.ui.spinner.Picker;
import com.codename1.ui.util.UITimer;
import com.codename1.ui.util.Resources;
import java.util.Calendar;
import java.util.Date;
import java.util.Hashtable;
import net.informaticalibera.streakapp.native_.AndroidUsageBridge;

/**
 * Codename One entry point for the study streak tracker.
 *
 * <p>The UI remains in common Java while Android-specific app launch and usage
 * statistics are isolated in {@link AndroidUsageBridge}.</p>
 */
public class StreakApp extends Lifecycle {
    private static final long MINUTE = 60_000L;
    private static final int DEFAULT_MINUTES = 3;
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
    private static final int RESUME_REFRESH_DELAY_MILLIS = 1500;
    private static final int RETURN_REFRESH_INTERVAL_MILLIS = 2000;
    private static final int RETURN_REFRESH_ATTEMPTS = 3;
    private static final int MIN_REFRESH_BUSY_MILLIS = 500;
    private static final int ANDROID_BACK_KEY = -23452;

    private static final StudyApp[] DEFAULT_APPS = {
        new StudyApp("AnkiDroid", "com.ichi2.anki", DEFAULT_MINUTES),
        new StudyApp("Duolingo", "com.duolingo", DEFAULT_MINUTES),
        new StudyApp("Drops", "com.languagedrops.drops.international", DEFAULT_MINUTES),
        new StudyApp("Rosetta Stone", "air.com.rosettastone.mobile.CoursePlayer", DEFAULT_MINUTES),
        new StudyApp("Talkpal", "ai.talkpal", DEFAULT_MINUTES),
        new StudyApp("Quizlet", "com.quizlet.quizletandroid", DEFAULT_MINUTES)
    };

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
        styleLightweightPicker();
        applyFontScalePreference();
    }

    @Override
    public void start() {
        super.start();
        Form current = com.codename1.ui.CN.getCurrentForm();
        if (booted && (current == homeForm || getCurrentForm() == homeForm || homeForm == null)) {
            refreshHomeAfterResume();
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
        styleLightweightPicker();
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

    private void styleLightweightPicker() {
        boolean dark = isDarkThemeActive();
        int surface = dark ? 0x171d22 : 0xffffff;
        int raised = dark ? 0x334155 : 0xe5edf8;
        int pressed = dark ? 0x475569 : 0xd5e2f3;
        int border = dark ? 0x475569 : 0xc7d4e6;
        int text = dark ? 0xeef2f6 : 0x111827;
        int muted = dark ? 0xaeb8c2 : 0x526070;

        styleFlat("PickerDialog", surface, text);
        styleFlat("PickerDialogContent", surface, text);
        styleFlat("PickerDialogTablet", surface, text);
        styleFlat("PickerDialogContentTablet", surface, text);
        styleFlat("PickerButtonBar", surface, text);
        styleFlat("PickerButtonBarTablet", surface, text);
        styleFlat("Spinner3DOverlay", surface, text);
        styleSpinnerRows(surface, text, muted);
        stylePickerButton("PickerButton", raised, pressed, border, text);
        stylePickerButton("PickerButtonTablet", raised, pressed, border, text);
    }

    private boolean isDarkThemeActive() {
        String mode = Preferences.get(PREF_THEME_MODE, THEME_SYSTEM);
        if (THEME_DARK.equals(mode)) {
            return true;
        }
        if (THEME_LIGHT.equals(mode)) {
            return false;
        }
        return Boolean.TRUE.equals(Display.getInstance().isDarkMode());
    }

    private void styleFlat(String uiid, int background, int foreground) {
        Style style = UIManager.getInstance().getComponentStyle(uiid);
        style.setBgColor(background);
        style.setBgTransparency(255);
        style.setFgColor(foreground);
        style.setBorder(Border.createEmpty());
        style.setMargin(0, 0, 0, 0);
        style.setPadding(0, 0, 0, 0);
        UIManager.getInstance().setComponentStyle(uiid, style);
    }

    private void styleSpinnerRows(int background, int text, int muted) {
        Style row = UIManager.getInstance().getComponentStyle("Spinner3DRow");
        row.setBgColor(background);
        row.setBgTransparency(255);
        row.setFgColor(muted);
        row.setBorder(Border.createEmpty());
        row.setAlignment(Component.CENTER);
        UIManager.getInstance().setComponentStyle("Spinner3DRow", row);

        Style selected = UIManager.getInstance().getComponentSelectedStyle("Spinner3DRow");
        selected.setBgColor(background);
        selected.setBgTransparency(255);
        selected.setFgColor(text);
        selected.setBorder(Border.createEmpty());
        selected.setAlignment(Component.CENTER);
        UIManager.getInstance().setComponentSelectedStyle("Spinner3DRow", selected);
    }

    private void stylePickerButton(String uiid, int background, int pressedBackground, int borderColor, int text) {
        Style normal = UIManager.getInstance().getComponentStyle(uiid);
        normal.setBgColor(background);
        normal.setBgTransparency(255);
        normal.setFgColor(text);
        normal.setBorder(Border.createLineBorder(1, borderColor));
        normal.setMargin(1, 1, 1, 1);
        normal.setPadding(2, 2, 2, 2);
        UIManager.getInstance().setComponentStyle(uiid, normal);

        Style selected = new Style(normal);
        selected.setBgColor(pressedBackground);
        UIManager.getInstance().setComponentSelectedStyle(uiid, selected);

        Style press = new Style(selected);
        UIManager.getInstance().setComponentStyle(uiid, press, "press");

        Style disabled = new Style(normal);
        disabled.setFgColor(text);
        UIManager.getInstance().setComponentStyle(uiid, disabled, "dis");
    }

    private void setFontScalePercent(int requestedPercent) {
        int currentPercent = currentFontScalePercent();
        int nextPercent = Math.max(MIN_FONT_SCALE_PERCENT, Math.min(MAX_FONT_SCALE_PERCENT, requestedPercent));
        if (nextPercent == currentPercent) {
            return;
        }
        Form currentForm = getCurrentForm();
        if (currentForm != null) {
            AutoShrinkSupport.prepareForThemeRefresh(currentForm);
        }
        Preferences.set(PREF_FONT_SCALE, nextPercent / 100f);
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
        form.revalidate();
        form.revalidateLater();
        form.repaint();
        AutoShrinkSupport.resetAndApplyLater(form);
    }

    private void refreshHomeAsync(boolean showToast) {
        if (refreshInProgress) {
            return;
        }
        refreshInProgress = true;
        long refreshStarted = System.currentTimeMillis();
        setRefreshBusy(true);
        if (homeForm == null) {
            showLoadingHome();
        }

        startThread(() -> {
            try {
                DayProgress progress = computeTodayProgress();
                sleepUntilMinimumBusyTime(refreshStarted);
                callSerially(() -> {
                    refreshInProgress = false;
                    setRefreshBusy(false);
                    lastProgress = progress;
                    showHome(progress, showToast);
                });
            } catch (Throwable t) {
                Log.e(t);
                sleepUntilMinimumBusyTime(refreshStarted);
                callSerially(() -> {
                    refreshInProgress = false;
                    setRefreshBusy(false);
                    ToastBar.showErrorMessage(text("toast.refresh.failed", "Refresh failed"));
                });
            }
        }, "StreakApp Refresh").start();
    }

    private void sleepUntilMinimumBusyTime(long refreshStarted) {
        long elapsed = System.currentTimeMillis() - refreshStarted;
        if (elapsed < MIN_REFRESH_BUSY_MILLIS) {
            Util.sleep(MIN_REFRESH_BUSY_MILLIS - (int) elapsed);
        }
    }

    private void refreshHomeAfterResume() {
        refreshHomeAsync(false);
        startThread(() -> {
            Util.sleep(RESUME_REFRESH_DELAY_MILLIS);
            callSerially(() -> {
                if (com.codename1.ui.CN.getCurrentForm() == homeForm) {
                    refreshHomeAsync(false);
                }
            });
        }, "StreakApp Resume Refresh").start();
    }

    private DayProgress computeTodayProgress() {
        long todayStart = startOfToday();
        DayProgress progress = loadProgress(todayStart, System.currentTimeMillis());
        if (progress.usageAccessGranted) {
            reconcileStreak(progress);
            return loadProgress(todayStart, System.currentTimeMillis());
        }
        if (Preferences.get(PREF_LAST_DAY_START, 0L) == 0L) {
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
        if (!progress.usageAccessGranted) {
            content.add(createUsageAccessPanel());
        }
        content.add(sectionTitle(text("section.studyApps", "Study apps")));
        for (int i = 0; i < progress.apps.length; i++) {
            if (progress.apps[i].enabled) {
                content.add(createAppCard(progress.apps[i]));
            }
        }
        if (progress.enabledCount == 0) {
            content.add(infoPanel(
                    text("empty.apps.title", "No active apps"),
                    text("empty.apps.body", "Open Settings from the menu and enable at least one app.")));
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
        hero.add(span(text("hero.appsStatus", "Apps completed today: {0}/{1}",
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

    private Container createAppCard(AppProgress progress) {
        Container card = new Container(new BorderLayout());
        card.setUIID("StudyCard");

        Container details = new Container(BoxLayout.y());
        details.add(label(progress.app.name, "AppName"));
        details.add(span(appStatusText(progress), "AppMeta"));
        details.add(label(text("appCard.durationTarget", "{0} / {1} min",
                formatDuration(progress.foregroundMillis), progress.targetMinutes),
                progress.complete ? "GoodText" : "MutedText"));

        Button open = button(text("button.open", "Open"), "SmallButton", FontImage.MATERIAL_OPEN_IN_NEW);
        open.setEnabled(progress.installed);
        open.addActionListener(e -> launchApp(progress));

        card.add(BorderLayout.CENTER, details);
        card.add(BorderLayout.EAST, FlowLayout.encloseCenter(open));
        return card;
    }

    private String appStatusText(AppProgress progress) {
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

    private void launchApp(AppProgress progress) {
        if (!progress.installed) {
            Dialog.show(text("dialog.appNotFound.title", "App not found"),
                    text("dialog.appNotFound.body", "I cannot open {0}.", progress.app.name),
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
        if (!isNativeReady() || !usageBridge.launchPackage(progress.app.packageName)) {
            Dialog.show(text("dialog.launchFailed.title", "Launch failed"),
                    text("dialog.launchFailed.body", "Android did not open {0}.", progress.app.name),
                    text("button.ok", "OK"), null);
            return;
        }
        scheduleReturnRefreshes();
    }

    private void scheduleReturnRefreshes() {
        if (returnRefreshTimer != null) {
            returnRefreshTimer.cancel();
        }
        returnRefreshAttemptsRemaining = RETURN_REFRESH_ATTEMPTS;
        returnRefreshTimer = UITimer.timer(RETURN_REFRESH_INTERVAL_MILLIS, true, homeForm, () -> {
            if (Display.getInstance().isMinimized()
                    || com.codename1.ui.CN.getCurrentForm() != homeForm) {
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

    private void showSettings() {
        settingsForm = createBackForm(text("settings.title", "Settings"));
        Container content = createScreenContent();
        content.add(sectionTitle(text("settings.appearance", "Appearance")));
        content.add(createThemePickerRow());
        content.add(createLanguagePickerRow());
        content.add(createFontScaleRow());
        content.add(sectionTitle(text("settings.daySection", "Day boundary")));
        content.add(span(text("settings.dayMode.body", "Calendar days end at midnight. Study days end at 4:00 AM."), "BodyText"));
        content.add(createDayModePickerRow());
        content.add(sectionTitle(text("settings.dailyTargets", "Daily targets")));
        content.add(span(text("settings.dailyTargets.body", "Choose the apps that count toward the streak and set the minimum minutes for each app."), "BodyText"));

        for (int i = 0; i < DEFAULT_APPS.length; i++) {
            StudyApp app = DEFAULT_APPS[i];
            Container row = new Container(new BorderLayout());
            row.setUIID("SettingsRow");

            CheckBox enabled = new CheckBox(app.name);
            enabled.setUIID("SettingsCheckBox");
            enabled.getAllStyles().setBgTransparency(0);
            enabled.setSelected(isAppEnabled(app));
            enabled.addActionListener(e -> Preferences.set(enabledKey(app), enabled.isSelected()));

            TextField minutes = new TextField(String.valueOf(targetMinutes(app)), text("settings.minutesHint", "min"), 3, TextArea.NUMERIC);
            minutes.setUIID("SettingsField");
            minutes.addDataChangedListener((type, index) -> saveMinutesIfValid(app, minutes, false));
            minutes.setDoneListener(e -> saveMinutesIfValid(app, minutes, true));
            minutes.addFocusListener(new FocusListener() {
                @Override
                public void focusGained(com.codename1.ui.Component cmp) {
                }

                @Override
                public void focusLost(com.codename1.ui.Component cmp) {
                    saveMinutesIfValid(app, minutes, true);
                }
            });
            row.add(BorderLayout.CENTER, enabled);
            row.add(BorderLayout.EAST, minutes);
            content.add(row);
        }

        content.add(infoPanel(text("settings.freezes.title", "Streak freezes"),
                text("settings.freezes.body", "Every {0} completed days earns one freeze. The maximum available is {1}.",
                        FREEZE_EVERY_COMPLETED_DAYS, MAX_FREEZES)));

        Button reset = button(text("button.resetStreak", "Reset streak"), "DangerButton", FontImage.MATERIAL_RESTORE);
        reset.addActionListener(e -> resetStreakData());

        content.add(reset);
        settingsForm.add(BorderLayout.CENTER, content);
        showForm(settingsForm, false);
    }

    private Form createBackForm(String title) {
        Form form = new Form(title, new BorderLayout()) {
            @Override
            public void keyReleased(int keyCode) {
                // The Android port translates the system Back button before
                // CN1 dispatches it to the current form.
                if (keyCode == ANDROID_BACK_KEY) {
                    navigateHome();
                    return;
                }
                super.keyReleased(keyCode);
            }
        };
        form.setScrollVisible(false);
        form.setTensileDragEnabled(false);
        form.setAlwaysTensile(false);
        form.setMinimizeOnBack(false);
        Command back = form.getToolbar().setBackCommand(text("menu.home", "Home"), e -> navigateHome());
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
            setFontScalePercent(currentFontScalePercent() - FONT_SCALE_STEP_PERCENT);
            updateFontScaleValue(value);
        });
        reset.addActionListener(e -> {
            setFontScalePercent(100);
            updateFontScaleValue(value);
        });
        larger.addActionListener(e -> {
            setFontScalePercent(currentFontScalePercent() + FONT_SCALE_STEP_PERCENT);
            updateFontScaleValue(value);
        });

        controls.add(smaller);
        controls.add(value);
        controls.add(larger);
        row.add(controls);
        row.add(reset);
        return row;
    }

    private void updateFontScaleValue(Label value) {
        value.setText(fontScaleText());
        Form current = getCurrentForm();
        if (current != null) {
            current.setShouldCalcPreferredSize(true);
            current.revalidate();
            current.revalidateLater();
            AutoShrinkSupport.resetAndApplyLater(current);
        }
    }

    private Container createDayModePickerRow() {
        Container row = new Container(new BorderLayout());
        row.setUIID("SettingsRow");
        row.add(BorderLayout.CENTER, label(text("settings.dayMode", "Day type"), "SettingsLabel"));

        Picker picker = new Picker();
        picker.setUIID("SettingsPicker");
        picker.setUseLightweightPopup(true);
        picker.setType(Display.PICKER_TYPE_STRINGS);
        picker.setStrings(dayModeLabel(DAY_CALENDAR), dayModeLabel(DAY_STUDY));
        picker.setSelectedString(dayModeLabel(Preferences.get(PREF_DAY_MODE, DAY_CALENDAR)));
        picker.addActionListener(e -> {
            Preferences.set(PREF_DAY_MODE, dayModeFromLabel(picker.getSelectedString()));
            Preferences.set(PREF_LAST_DAY_START, startOfToday());
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
        return text("settings.textSizeValue", "{0}%", currentFontScalePercent());
    }

    private void navigateHome() {
        if (lastProgress != null) {
            showHome(lastProgress, false, true);
        } else {
            showLoadingHome(true);
        }
        refreshHomeAsync(false);
    }

    private boolean saveMinutesIfValid(StudyApp app, TextField field, boolean showErrors) {
        String raw = field.getText();
        int minutes;
        try {
            minutes = Integer.parseInt(raw == null ? "" : raw.trim());
        } catch (NumberFormatException ex) {
            if (showErrors) {
                restoreMinutesField(app, field, "dialog.invalidValue.body");
            }
            return false;
        }
        if (minutes < 1 || minutes > 240) {
            if (showErrors) {
                restoreMinutesField(app, field, "dialog.invalidRange.body");
            }
            return false;
        }
        Preferences.set(minutesKey(app), minutes);
        return true;
    }

    private void restoreMinutesField(StudyApp app, TextField field, String messageKey) {
        String fallback = "dialog.invalidRange.body".equals(messageKey)
                ? "Minutes for {0} must be between 1 and 240."
                : "Check the minutes for {0}.";
        Dialog.show(text("dialog.invalidValue.title", "Invalid value"),
                text(messageKey, fallback, app.name),
                text("button.ok", "OK"), null);
        field.setText(String.valueOf(targetMinutes(app)));
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
            Preferences.delete(dayCompleteKey(day));
        }
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
        day.apps = new AppProgress[DEFAULT_APPS.length];
        day.usageAccessGranted = isNativeReady() && usageBridge.isUsageAccessGranted();
        for (int i = 0; i < DEFAULT_APPS.length; i++) {
            StudyApp app = DEFAULT_APPS[i];
            AppProgress p = new AppProgress();
            p.app = app;
            p.enabled = isAppEnabled(app);
            p.targetMinutes = targetMinutes(app);
            p.usageAccessGranted = day.usageAccessGranted;
            p.installed = !isNativeReady() || safeIsLaunchable(app.packageName);
            p.foregroundMillis = p.enabled && p.installed && day.usageAccessGranted
                    ? safeForegroundMillis(app.packageName, fromMillis, toMillis)
                    : 0L;
            p.complete = p.enabled && p.installed && p.foregroundMillis >= p.targetMinutes * MINUTE;
            day.apps[i] = p;
            if (p.enabled) {
                day.enabledCount++;
                if (p.complete) {
                    day.completeCount++;
                }
            }
        }
        day.complete = day.enabledCount > 0 && day.completeCount == day.enabledCount;
        return day;
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
            finalizePastDay(cursor);
            cursor = addDays(cursor, 1);
            guard++;
        }
        Preferences.set(PREF_LAST_DAY_START, todayStart);
        if (today.complete) {
            markDayComplete(todayStart, true);
        }
    }

    private void finalizePastDay(long dayStart) {
        if (Preferences.get(dayCompleteKey(dayStart), false)) {
            return;
        }
        DayProgress past = loadProgress(dayStart, addDays(dayStart, 1));
        if (past.complete) {
            markDayComplete(dayStart, false);
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

    private boolean isAppEnabled(StudyApp app) {
        return Preferences.get(enabledKey(app), true);
    }

    private int targetMinutes(StudyApp app) {
        return Preferences.get(minutesKey(app), app.defaultMinutes);
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
        Calendar c = Calendar.getInstance();
        c.setTime(new Date(dayStart));
        return "day.complete." + c.get(Calendar.YEAR) + "." + (c.get(Calendar.MONTH) + 1) + "." + c.get(Calendar.DAY_OF_MONTH);
    }

    private String enabledKey(StudyApp app) {
        return "app.enabled." + app.packageName;
    }

    private String minutesKey(StudyApp app) {
        return "app.minutes." + app.packageName;
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

    private static final class StudyApp {
        final String name;
        final String packageName;
        final int defaultMinutes;

        StudyApp(String name, String packageName, int defaultMinutes) {
            this.name = name;
            this.packageName = packageName;
            this.defaultMinutes = defaultMinutes;
        }
    }

    private static final class DayProgress {
        AppProgress[] apps;
        boolean usageAccessGranted;
        boolean complete;
        int enabledCount;
        int completeCount;
    }

    private static final class AppProgress {
        StudyApp app;
        boolean enabled;
        boolean installed;
        boolean usageAccessGranted;
        boolean complete;
        int targetMinutes;
        long foregroundMillis;
    }
}

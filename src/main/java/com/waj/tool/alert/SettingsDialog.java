package com.waj.tool.alert;

import com.waj.tool.i18n.Messages;
import com.waj.tool.persistence.AppConfig;
import com.waj.tool.persistence.AppConfigStore;
import com.waj.tool.util.AppTheme;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.stream.Collectors;

/** Modal dialog for alert thresholds, per-rule enable flags, notification toggle, and trusted-AP management. */
public final class SettingsDialog {

    private SettingsDialog() {
    }

    public static void show(Window owner, AppConfig config, TrustedApRegistry trustedApRegistry, Runnable onSaved) {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle(Messages.get("settings.dialog.title"));

        Spinner<Integer> rssiThresholdSpinner = new Spinner<>(-100, -30, config.rssiThresholdDbm);
        rssiThresholdSpinner.setEditable(true);
        commitOnFocusLoss(rssiThresholdSpinner);

        Spinner<Double> congestionSpinner = new Spinner<>(
                new SpinnerValueFactory.DoubleSpinnerValueFactory(0, 500, config.channelCongestionThreshold, 5));
        congestionSpinner.setEditable(true);
        commitOnFocusLoss(congestionSpinner);

        CheckBox rssiAlertCheck = new CheckBox(Messages.get("settings.check.rssiAlert"));
        rssiAlertCheck.setSelected(config.rssiAlertEnabled);
        CheckBox rogueApCheck = new CheckBox(Messages.get("settings.check.rogueAp"));
        rogueApCheck.setSelected(config.rogueApAlertEnabled);
        CheckBox newSsidCheck = new CheckBox(Messages.get("settings.check.newSsid"));
        newSsidCheck.setSelected(config.newSsidAlertEnabled);
        CheckBox congestionCheck = new CheckBox(Messages.get("settings.check.congestion"));
        congestionCheck.setSelected(config.channelCongestionAlertEnabled);
        CheckBox notifyCheck = new CheckBox(Messages.get("settings.check.notifications"));
        notifyCheck.setSelected(config.windowsNotificationsEnabled);

        TextField pingHostField = new TextField(config.defaultPingHost);
        pingHostField.setPromptText(Messages.get("settings.pingHost.prompt"));

        // Language names are shown in their own native form regardless of the currently active UI
        // language (the same convention every OS/app language picker uses) - a user who can't read
        // the current language still needs to find their own in the list, so these two literals are
        // deliberately NOT run through Messages.get().
        ComboBox<String> languageSelector = new ComboBox<>();
        languageSelector.getItems().setAll("日本語", "English");
        String originalLanguage = config.language;
        languageSelector.getSelectionModel().select("en".equals(config.language) ? "English" : "日本語");

        ListView<String> trustedList = new ListView<>();
        refreshTrustedList(trustedList, trustedApRegistry);
        Button untrustButton = new Button(Messages.get("settings.button.untrust"));
        untrustButton.setOnAction(e -> {
            String selected = trustedList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                trustedApRegistry.untrust(extractBssid(selected));
                refreshTrustedList(trustedList, trustedApRegistry);
            }
        });

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        int row = 0;
        form.addRow(row++, new Label(Messages.get("settings.label.rssiThreshold")), rssiThresholdSpinner);
        form.addRow(row++, new Label(Messages.get("settings.label.congestionThreshold")), congestionSpinner);
        form.addRow(row++, rssiAlertCheck);
        form.addRow(row++, rogueApCheck);
        form.addRow(row++, newSsidCheck);
        form.addRow(row++, congestionCheck);
        form.addRow(row++, notifyCheck);
        form.addRow(row++, new Label(Messages.get("settings.label.pingHost")), pingHostField);
        form.addRow(row++, new Label(Messages.get("settings.label.language")), languageSelector);

        VBox trustedBox = new VBox(6, new Label(Messages.get("settings.label.trustedList")), trustedList, untrustButton);

        Button saveButton = new Button(Messages.get("settings.button.save"));
        Button cancelButton = new Button(Messages.get("common.button.cancel"));
        HBox buttonBar = new HBox(8, saveButton, cancelButton);

        saveButton.setOnAction(e -> {
            config.rssiThresholdDbm = rssiThresholdSpinner.getValue();
            config.channelCongestionThreshold = congestionSpinner.getValue();
            config.rssiAlertEnabled = rssiAlertCheck.isSelected();
            config.rogueApAlertEnabled = rogueApCheck.isSelected();
            config.newSsidAlertEnabled = newSsidCheck.isSelected();
            config.channelCongestionAlertEnabled = congestionCheck.isSelected();
            config.windowsNotificationsEnabled = notifyCheck.isSelected();
            config.defaultPingHost = pingHostField.getText();
            config.language = "English".equals(languageSelector.getValue()) ? "en" : "ja";
            AppConfigStore.save(config);
            onSaved.run();
            if (!config.language.equals(originalLanguage)) {
                Alert restartNotice = new Alert(Alert.AlertType.INFORMATION, Messages.get("settings.language.restartNotice"));
                restartNotice.setHeaderText(null);
                AppTheme.apply(restartNotice);
                restartNotice.showAndWait();
            }
            stage.close();
        });
        cancelButton.setOnAction(e -> stage.close());

        VBox root = new VBox(12, form, trustedBox, buttonBar);
        root.setPadding(new Insets(12));
        Scene scene = new Scene(root, 520, 620);
        AppTheme.apply(scene);
        stage.setScene(scene);
        stage.showAndWait();
    }

    /**
     * Editable JavaFX Spinners don't commit their editor's typed text on their own - only on
     * Enter, or via the up/down arrows - so a user who types a new value and clicks straight to
     * "保存" (a very natural flow) would have {@code spinner.getValue()} silently return the
     * *previous* value while the field still visually shows what they typed, with no error or
     * indication anything was wrong. Committing on focus-loss (tab/click away, not just Enter)
     * closes that gap. Falls back to leaving the value unchanged if the typed text doesn't parse.
     *
     * <p>{@code SpinnerValueFactory.setValue()} does not clamp to the spinner's configured
     * min/max the way its up/down-arrow {@code increment()}/{@code decrement()} do - committing
     * an out-of-range typed value directly would let e.g. an RSSI threshold of 999 (spinner range
     * -100..-30) slip into the saved config, so the parsed value is clamped the same way the
     * concrete Integer/Double factories already clamp internally.
     */
    private static <T> void commitOnFocusLoss(Spinner<T> spinner) {
        spinner.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (isFocused) {
                return;
            }
            SpinnerValueFactory<T> factory = spinner.getValueFactory();
            if (factory == null) {
                return;
            }
            try {
                T parsed = factory.getConverter().fromString(spinner.getEditor().getText());
                if (parsed != null) {
                    factory.setValue(clamp(factory, parsed));
                }
            } catch (Exception ignored) {
                // Unparseable text - leave the last valid value in place rather than crash.
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T clamp(SpinnerValueFactory<T> factory, T value) {
        if (factory instanceof SpinnerValueFactory.IntegerSpinnerValueFactory intFactory && value instanceof Integer intValue) {
            return (T) Integer.valueOf(Math.max(intFactory.getMin(), Math.min(intFactory.getMax(), intValue)));
        }
        if (factory instanceof SpinnerValueFactory.DoubleSpinnerValueFactory dblFactory && value instanceof Double dblValue) {
            return (T) Double.valueOf(Math.max(dblFactory.getMin(), Math.min(dblFactory.getMax(), dblValue)));
        }
        return value;
    }

    private static void refreshTrustedList(ListView<String> listView, TrustedApRegistry registry) {
        listView.getItems().setAll(registry.all().stream()
                .map(t -> t.ssid + " (" + t.bssid + ")")
                .collect(Collectors.toList()));
    }

    private static String extractBssid(String displayText) {
        int start = displayText.lastIndexOf('(');
        int end = displayText.lastIndexOf(')');
        return (start >= 0 && end > start) ? displayText.substring(start + 1, end) : displayText;
    }
}

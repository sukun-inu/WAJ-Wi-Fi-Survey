package com.opensitesurvey.tool.alert;

import com.opensitesurvey.tool.i18n.Messages;
import com.opensitesurvey.tool.util.MonoTableCells;
import com.opensitesurvey.tool.util.RiskColors;
import com.opensitesurvey.tool.util.TooltipSupport;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;

import java.util.List;

/** Scrolling log of fired alerts (newest first), color-coded by severity. */
public final class AlertsView {

    private final BorderPane root = new BorderPane();
    private final TableView<Alert> table = new TableView<>();
    private final ObservableList<Alert> items = FXCollections.observableArrayList();

    public AlertsView(Runnable onSettingsRequested) {
        buildTable();

        Button settingsButton = new Button(Messages.get("common.button.settingsDialog"));
        settingsButton.setOnAction(e -> onSettingsRequested.run());
        TooltipSupport.set(settingsButton, Messages.get("tooltip.alerts.settings"));
        TooltipSupport.set(table, Messages.get("tooltip.alerts.table"));
        HBox top = new HBox(8, new Label(Messages.get("alerts.label.title")), settingsButton);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(8));

        root.setTop(top);
        root.setCenter(table);
    }

    public javafx.scene.Node getRoot() {
        return root;
    }

    /** Must be called on the JavaFX Application thread. */
    public void addAlerts(List<Alert> alerts) {
        if (alerts.isEmpty()) {
            return;
        }
        items.addAll(0, alerts);
        if (items.size() > 2000) {
            items.remove(2000, items.size());
        }
    }

    private void buildTable() {
        TableColumn<Alert, String> timeCol = new TableColumn<>(Messages.get("common.column.timestamp"));
        timeCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().timestamp().toString()));
        timeCol.setPrefWidth(220);
        MonoTableCells.applyTo(timeCol);

        TableColumn<Alert, AlertSeverity> severityCol = new TableColumn<>(Messages.get("alerts.column.severity"));
        severityCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().severity()));
        severityCol.setPrefWidth(90);
        severityCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(AlertSeverity severity, boolean empty) {
                super.updateItem(severity, empty);
                if (empty || severity == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                setText(severity.name());
                setStyle(RiskColors.forAlertSeverity(severity).toCss());
            }
        });

        TableColumn<Alert, String> categoryCol = new TableColumn<>(Messages.get("alerts.column.category"));
        categoryCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().category()));
        categoryCol.setPrefWidth(180);

        TableColumn<Alert, String> messageCol = new TableColumn<>(Messages.get("alerts.column.message"));
        messageCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().message()));
        messageCol.setPrefWidth(560);

        table.getColumns().setAll(List.of(timeCol, severityCol, categoryCol, messageCol));
        table.setItems(items);
        table.setPlaceholder(new Label(Messages.get("alerts.placeholder.empty")));
    }
}

package com.waj.tool.security;

import com.waj.tool.alert.TrustedApRegistry;
import com.waj.tool.i18n.Messages;
import com.waj.tool.model.ApSnapshot;
import com.waj.tool.model.ScanSnapshot;
import com.waj.tool.util.CategoricalColorPalette;
import com.waj.tool.util.MonoTableCells;
import com.waj.tool.util.RiskColors;
import com.waj.tool.util.TooltipSupport;
import com.waj.tool.util.VendorLookup;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Lists every visible AP with its classified security type, highlighting open/weak-encryption ones. */
public final class SecurityAuditView {

    private final BorderPane root = new BorderPane();
    private final TableView<ApSnapshot> table = new TableView<>();
    private final ObservableList<ApSnapshot> items = FXCollections.observableArrayList();
    private final Label summaryLabel = new Label(Messages.get("common.status.waitingForScan"));
    private final TrustedApRegistry trustedApRegistry;
    private final CategoricalColorPalette colorPalette;

    public SecurityAuditView(TrustedApRegistry trustedApRegistry, CategoricalColorPalette colorPalette) {
        this.trustedApRegistry = trustedApRegistry;
        this.colorPalette = colorPalette;
        buildTable();
        TooltipSupport.set(summaryLabel, Messages.get("tooltip.security.summary"));
        TooltipSupport.set(table, Messages.get("tooltip.security.table"));
        VBox top = new VBox(6, new Label(Messages.get("security.title")), summaryLabel);
        top.setPadding(new Insets(8));
        VBox center = new VBox(table);
        center.getStyleClass().add("card");
        javafx.scene.layout.VBox.setVgrow(table, javafx.scene.layout.Priority.ALWAYS);
        root.setTop(top);
        root.setCenter(center);
    }

    public javafx.scene.Node getRoot() {
        return root;
    }

    private void buildTable() {
        // Same identity color as the Dashboard's curves/table swatch for this BSSID, so an
        // engineer can visually cross-reference "this risky AP" with its Dashboard trace.
        TableColumn<ApSnapshot, ApSnapshot> colorCol = new TableColumn<>("");
        colorCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue()));
        colorCol.setSortable(false);
        colorCol.setPrefWidth(22);
        colorCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(ApSnapshot ap, boolean empty) {
                super.updateItem(ap, empty);
                setGraphic(empty || ap == null ? null : new Rectangle(12, 12, colorPalette.colorFor(ap.bssid())));
            }
        });

        TableColumn<ApSnapshot, String> ssidCol = new TableColumn<>("SSID");
        ssidCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(
                d.getValue().ssid().isEmpty() ? "<hidden>" : d.getValue().ssid()));
        ssidCol.setPrefWidth(180);

        TableColumn<ApSnapshot, String> bssidCol = new TableColumn<>("BSSID");
        bssidCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().bssid()));
        bssidCol.setPrefWidth(140);

        // Surfaced here (not just on Dashboard) because vendor mismatch is a security-relevant
        // signal: an AP claiming a trusted SSID from an unexpected hardware vendor's OUI is a
        // classic evil-twin/rogue-AP tell, worth a quick visual scan alongside the security type.
        TableColumn<ApSnapshot, String> vendorCol = new TableColumn<>(Messages.get("dashboard.column.vendor"));
        vendorCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(
                java.util.Objects.requireNonNullElse(VendorLookup.vendorFor(d.getValue().bssid()), "")));
        vendorCol.setPrefWidth(150);

        TableColumn<ApSnapshot, String> bandCol = new TableColumn<>("Band/Ch");
        bandCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().band() + " / ch" + d.getValue().channel()));
        bandCol.setPrefWidth(110);

        TableColumn<ApSnapshot, Number> rssiCol = new TableColumn<>("RSSI(dBm)");
        rssiCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().rssiDbm()));
        rssiCol.setPrefWidth(80);
        MonoTableCells.applyTo(rssiCol);

        TableColumn<ApSnapshot, SecurityType> secCol = new TableColumn<>(Messages.get("security.column.type"));
        secCol.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().securityType()));
        secCol.setPrefWidth(170);
        secCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(SecurityType type, boolean empty) {
                super.updateItem(type, empty);
                if (empty || type == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                setText(type.label());
                setStyle(RiskColors.forSecurityRisk(type.riskLevel()).toCss());
            }
        });

        table.getColumns().setAll(List.of(colorCol, ssidCol, bssidCol, vendorCol, bandCol, rssiCol, secCol));
        table.setItems(items);
        table.setPlaceholder(new Label(Messages.get("common.status.waitingForScan")));
        table.setRowFactory(tv -> {
            TableRow<ApSnapshot> row = new TableRow<>();
            MenuItem trustItem = new MenuItem();
            trustItem.setOnAction(e -> {
                ApSnapshot ap = row.getItem();
                if (ap == null) {
                    return;
                }
                if (trustedApRegistry.isTrusted(ap.bssid())) {
                    trustedApRegistry.untrust(ap.bssid());
                } else {
                    trustedApRegistry.trust(ap.ssid(), ap.bssid());
                }
            });
            ContextMenu menu = new ContextMenu(trustItem);
            row.setOnContextMenuRequested(e -> {
                ApSnapshot ap = row.getItem();
                if (ap != null) {
                    trustItem.setText(trustedApRegistry.isTrusted(ap.bssid())
                            ? Messages.get("dashboard.contextMenu.untrust") : Messages.get("dashboard.contextMenu.trust"));
                }
            });
            row.contextMenuProperty().bind(
                    javafx.beans.binding.Bindings.when(row.emptyProperty())
                            .then((ContextMenu) null)
                            .otherwise(menu));
            return row;
        });
    }

    /** Must be called on the JavaFX Application thread. */
    public void onSnapshot(ScanSnapshot snapshot) {
        items.setAll(snapshot.accessPoints());

        Map<SecurityType.RiskLevel, Long> byRisk = new EnumMap<>(SecurityType.RiskLevel.class);
        long openCount = 0;
        long wepCount = 0;
        for (ApSnapshot ap : snapshot.accessPoints()) {
            byRisk.merge(ap.securityType().riskLevel(), 1L, Long::sum);
            if (ap.securityType() == SecurityType.OPEN) {
                openCount++;
            } else if (ap.securityType() == SecurityType.WEP) {
                wepCount++;
            }
        }
        summaryLabel.setText(Messages.get("security.summary",
                snapshot.accessPoints().size(), openCount, wepCount,
                byRisk.getOrDefault(SecurityType.RiskLevel.HIGH, 0L)));
    }
}

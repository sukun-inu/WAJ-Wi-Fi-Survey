package com.opensitesurvey.tool;

import com.opensitesurvey.tool.i18n.Messages;
import com.opensitesurvey.tool.util.AppTheme;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Lists the third-party libraries this app bundles and redistributes, along with each one's
 * license - required disclosure for the LGPL/MPL-licensed dependencies (OpenPDF, JNA) and good
 * practice for the rest. Notices are the standard short form (name/version/license + where to get
 * the full license text), not the full legal text of each license inline - the same convention
 * most desktop apps' "third-party notices" screens use.
 */
public final class LicenseDialog {

    private record Entry(String name, String version, String license, String noticeUrl) {
    }

    private static final Entry[] ENTRIES = {
            new Entry("OpenJFX (javafx-controls / javafx-graphics / javafx-swing)", "21.0.11",
                    "GNU General Public License v2 with the Classpath Exception",
                    "https://openjdk.org/legal/gplv2+ce.html"),
            new Entry("JNA (Java Native Access) / JNA Platform", "5.19.1",
                    "Apache License 2.0 (dual-licensed with LGPL 2.1 - this app uses it under Apache 2.0)",
                    "https://www.apache.org/licenses/LICENSE-2.0"),
            new Entry("Jackson Databind", "2.22.0",
                    "Apache License 2.0",
                    "https://www.apache.org/licenses/LICENSE-2.0"),
            new Entry("SQLite JDBC (Xerial)", "3.53.2.0",
                    "Apache License 2.0",
                    "https://www.apache.org/licenses/LICENSE-2.0"),
            new Entry("OpenPDF", "3.0.5",
                    "GNU Lesser General Public License 2.1 (dual-licensed with MPL 2.0 - this app uses it under LGPL 2.1)",
                    "https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html"),
    };

    private LicenseDialog() {
    }

    public static void show(Window owner) {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle(Messages.get("license.dialog.title"));

        VBox content = new VBox(14);
        content.setPadding(new Insets(16));

        Label intro = new Label(Messages.get("license.intro"));
        intro.setWrapText(true);

        content.getChildren().add(intro);

        for (Entry entry : ENTRIES) {
            content.getChildren().add(new Separator());
            Label name = new Label(entry.name() + "  (v" + entry.version() + ")");
            name.setStyle("-fx-font-weight: bold;");
            Label license = new Label(Messages.get("license.entry.license", entry.license()));
            license.setWrapText(true);
            Label url = new Label(entry.noticeUrl());
            url.setWrapText(true);
            url.setStyle("-fx-text-fill: #4aa3df;");
            content.getChildren().addAll(name, license, url);
        }

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefViewportHeight(480);

        Scene scene = new Scene(scrollPane, 560, 520);
        AppTheme.apply(scene);
        stage.setScene(scene);
        stage.showAndWait();
    }
}

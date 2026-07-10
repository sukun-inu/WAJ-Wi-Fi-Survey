package com.waj.tool.util;

import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.util.Callback;

/**
 * Applies the shared {@code .mono-numeric} style class (Consolas, see {@code style.css}) to a
 * numeric TableColumn's cells. A TableColumn's own style class doesn't cascade to its TableCell
 * instances - they're direct children of the TableRow in the scene graph, not of the column - so
 * this has to be done via a cellFactory rather than {@code column.getStyleClass().add(...)}.
 *
 * <p>Only for columns that don't already need a custom cellFactory for something else (e.g. a
 * checkbox or color-swatch cell) - those add {@code "mono-numeric"} directly inline instead.
 */
public final class MonoTableCells {

    private MonoTableCells() {
    }

    public static <S, T> void applyTo(TableColumn<S, T> column) {
        Callback<TableColumn<S, T>, TableCell<S, T>> defaultFactory = column.getCellFactory();
        column.setCellFactory((Callback<TableColumn<S, T>, TableCell<S, T>>) col -> {
            TableCell<S, T> cell = defaultFactory.call(col);
            cell.getStyleClass().add("mono-numeric");
            return cell;
        });
    }
}

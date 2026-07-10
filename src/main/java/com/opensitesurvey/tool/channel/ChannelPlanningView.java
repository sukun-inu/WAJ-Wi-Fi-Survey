package com.opensitesurvey.tool.channel;

import com.opensitesurvey.tool.i18n.Messages;
import com.opensitesurvey.tool.model.ApSnapshot;
import com.opensitesurvey.tool.model.ScanSnapshot;
import com.opensitesurvey.tool.ui.dashboard.AnnotatedAreaChart;
import com.opensitesurvey.tool.util.CategoricalColorPalette;
import com.opensitesurvey.tool.util.ChannelUtil;
import com.opensitesurvey.tool.util.TooltipSupport;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Channel occupancy chart - each channel is drawn as a flat-topped "mesa" curve spanning its real
 * bandwidth on a frequency axis (matching how a channel plan is conventionally diagrammed, e.g.
 * Japan's 5GHz J52/J53/J56 sub-band figures), one curve per contributing AP so an engineer can see
 * not just "this channel is busy" but *which* SSID is on it. Curve height is that AP's
 * contribution to the channel's congestion score (same figure the hover breakdown shows), color is
 * the shared {@link CategoricalColorPalette} identity color used everywhere else in the app.
 *
 * <p>5GHz is split into three stacked panels (J52/J53/J56 - Japan's officially designated 5GHz
 * sub-bands), since those groups are non-contiguous in frequency and conventionally documented as
 * separate figures; 2.4GHz and 6GHz each get a single panel. A fixed pool of {@value
 * #MAX_PANELS} panel slots is created once and reused across band switches (matching Dashboard's
 * pattern of reusing chart/axis objects rather than recreating them) - unused slots are simply
 * hidden.
 */
public final class ChannelPlanningView {

    private static final int MAX_PANELS = 3;
    private static final double CHANNEL_WIDTH_24_MHZ = 22;
    private static final double CHANNEL_WIDTH_5_6_MHZ = 20;

    // 2.4GHz/6GHz have no official sub-band split like 5GHz's J52/J53/J56, so each is shown as one
    // panel spanning its whole allocated range. Reuses ChannelPlanner.SubBand purely as a plain
    // (label, channels, startMhz, endMhz) holder - same shape needed either way.
    private static final ChannelPlanner.SubBand GROUP_24 = new ChannelPlanner.SubBand(
            "2.4GHz", new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13}, 2400, 2485);
    private static final ChannelPlanner.SubBand GROUP_6 = new ChannelPlanner.SubBand(
            "6GHz", new int[]{1, 5, 9, 13, 17, 21, 25, 29, 33, 37, 41, 45, 49, 53, 57, 61, 65, 69, 73, 77, 81, 85, 89, 93},
            5925, 6425);

    private final BorderPane root = new BorderPane();
    private final ComboBox<String> bandSelector = new ComboBox<>();
    private final Label recommendationLabel = new Label(Messages.get("common.status.waitingForScan"));

    private final VBox hoverPanel = new VBox(3);
    private final Label hoverHeader = new Label();
    private final VBox hoverEntriesBox = new VBox(2);

    private final List<Panel> panels = new ArrayList<>();
    private final VBox panelsColumn = new VBox(8);

    private final CategoricalColorPalette colorPalette;

    private ScanSnapshot lastSnapshot;
    // Survives across refresh() rebuilds so a live poll tick doesn't blow away the breakdown the
    // user is currently reading - see ChannelPlanner.Recommendation usage below for why.
    private Integer hoveredChannel;

    /** One reusable chart slot: its own frequency axis, score axis, area chart, and annotations. */
    private static final class Panel {
        final NumberAxis xAxis = new NumberAxis();
        final NumberAxis yAxis = new NumberAxis();
        final AnnotatedAreaChart<Number, Number> chart = new AnnotatedAreaChart<>(xAxis, yAxis);
        final VBox box;
        final List<Node> annotations = new ArrayList<>();
        // Bumped every buildPanel() call and captured by that call's deferred runLater work -
        // if a second refresh() rebuilds this same panel before the first build's two nested
        // runLater passes have drained (e.g. the user flips bands rapidly, or a poll tick lands
        // mid-build), the stale pass can tell it's been superseded and skip instead of appending
        // orphaned labels/dividers from a build this panel no longer represents.
        long generation = 0;

        Panel() {
            chart.setLegendVisible(false);
            chart.setAnimated(false);
            chart.setCreateSymbols(false);
            xAxis.setLabel(Messages.get("common.axis.frequencyMhz"));
            xAxis.setAutoRanging(false);
            yAxis.setLabel(Messages.get("channelPlanning.axis.congestionScore"));
            box = new VBox(chart);
            box.getStyleClass().add("card");
            VBox.setVgrow(chart, Priority.ALWAYS);
        }
    }

    /** One AP's occupancy curve on one channel, kept around for post-layout coloring/labeling. */
    private record CurveEntry(String bssid, String ssid, int channel, double centerMhz,
                               double contribution, XYChart.Series<Number, Number> series) {
    }

    public ChannelPlanningView(CategoricalColorPalette colorPalette) {
        this.colorPalette = colorPalette;

        bandSelector.getItems().setAll("2.4GHz", "5GHz", "6GHz");
        bandSelector.getSelectionModel().select("2.4GHz");
        bandSelector.setOnAction(e -> {
            // hoveredChannel is restored purely by channel *number* (see refresh()), but channel
            // numbers collide across bands - 2.4GHz and 6GHz both include channel 13, for example.
            // Without resetting here, switching bands right after hovering a channel could pop up
            // a breakdown for an entirely different band/channel the user never touched, just
            // because it happens to share the same number.
            hoveredChannel = null;
            refresh();
        });
        TooltipSupport.set(bandSelector, Messages.get("tooltip.channelPlanning.bandSelector"));

        for (int i = 0; i < MAX_PANELS; i++) {
            Panel p = new Panel();
            panels.add(p);
            panelsColumn.getChildren().add(p.box);
        }
        VBox.setVgrow(panelsColumn, Priority.ALWAYS);
        TooltipSupport.install(panelsColumn, Messages.get("tooltip.channelPlanning.chart"));

        hoverHeader.getStyleClass().add("crosshair-header");
        hoverPanel.getStyleClass().add("crosshair-panel");
        hoverPanel.setMinWidth(220);
        hoverPanel.getChildren().addAll(hoverHeader, hoverEntriesBox);
        showHoverPlaceholder();
        TooltipSupport.set(hoverPanel, Messages.get("tooltip.channelPlanning.hoverPanel"));

        HBox toolbar = new HBox(6, bandSelector);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        VBox top = new VBox(6,
                new Label(Messages.get("channelPlanning.title")),
                toolbar, recommendationLabel);
        top.setPadding(new Insets(8));
        TooltipSupport.set(recommendationLabel, Messages.get("tooltip.channelPlanning.recommendation"));

        HBox mainRow = new HBox(4, panelsColumn, hoverPanel);
        HBox.setHgrow(panelsColumn, Priority.ALWAYS);

        root.setTop(top);
        root.setCenter(mainRow);
    }

    public javafx.scene.Node getRoot() {
        return root;
    }

    /** Must be called on the JavaFX Application thread. */
    public void onSnapshot(ScanSnapshot snapshot) {
        this.lastSnapshot = snapshot;
        refresh();
    }

    private void showHoverPlaceholder() {
        hoverHeader.setText(Messages.get("channelPlanning.hover.placeholder"));
        hoverEntriesBox.getChildren().clear();
    }

    private List<ChannelPlanner.SubBand> groupsFor(String band) {
        return switch (band) {
            case "5GHz" -> ChannelPlanner.SUBBANDS_5GHZ;
            case "6GHz" -> List.of(GROUP_6);
            default -> List.of(GROUP_24);
        };
    }

    private void refresh() {
        for (Panel p : panels) {
            for (Node n : p.annotations) {
                p.chart.removeAnnotation(n);
            }
            p.annotations.clear();
            p.chart.getData().clear();
        }
        if (lastSnapshot == null) {
            showHoverPlaceholder();
            return;
        }
        String band = bandSelector.getSelectionModel().getSelectedItem();
        List<ApSnapshot> aps = lastSnapshot.accessPoints();
        ChannelPlanner.Recommendation rec = ChannelPlanner.recommend(aps, band);
        List<ChannelPlanner.SubBand> groups = groupsFor(band);
        double channelWidth = band.equals("2.4GHz") ? CHANNEL_WIDTH_24_MHZ : CHANNEL_WIDTH_5_6_MHZ;

        for (int i = 0; i < MAX_PANELS; i++) {
            Panel p = panels.get(i);
            boolean active = i < groups.size();
            p.box.setVisible(active);
            p.box.setManaged(active);
            if (active) {
                buildPanel(p, groups.get(i), band, rec, channelWidth);
            }
        }

        // Restore whatever channel the user was already inspecting (if it still has at least one
        // contributing AP in this band's fresh scores) instead of unconditionally blanking the
        // panel on every poll tick - the old segment/curve Node is destroyed on every refresh
        // without firing mouseExited, and the new one won't fire mouseEntered until the mouse
        // actually moves again, so without this the panel would flicker back to its placeholder
        // every poll interval.
        List<ChannelPlanner.Recommendation.ApContribution> hoveredContributions =
                hoveredChannel == null ? null : rec.perChannelContributions().get(hoveredChannel);
        if (hoveredContributions != null && !hoveredContributions.isEmpty()) {
            showChannelBreakdown(hoveredChannel, rec);
        } else {
            hoveredChannel = null;
            showHoverPlaceholder();
        }

        recommendationLabel.setText(Messages.get("channelPlanning.recommendation", rec.channel(), rec.score()));
    }

    private void buildPanel(Panel p, ChannelPlanner.SubBand group, String band,
                             ChannelPlanner.Recommendation rec, double channelWidth) {
        long myGeneration = ++p.generation;
        p.chart.setTitle(String.format("%s (%.0f-%.0fMHz)", group.label(), group.startMhz(), group.endMhz()));

        int[] groupChannels = group.channels();
        double firstCenterMhz = ChannelUtil.channelToFrequencyMhz(band, groupChannels[0]);
        double lastCenterMhz = ChannelUtil.channelToFrequencyMhz(band, groupChannels[groupChannels.length - 1]);
        // Channels within one group are evenly spaced (2.4GHz: 5MHz/channel-number; 5GHz/6GHz
        // sub-bands: 20MHz per +4 channel-numbers) - using that exact spacing as the axis tick
        // unit, with a lower bound that's a whole number of ticks below the first channel's
        // center, makes every interior tick land precisely on a channel's center frequency instead
        // of wherever NumberAxis's own "nice number" auto-ranging would otherwise land. That's what
        // lets the tick label below show the channel number, not just an arbitrary MHz value.
        double spacingMhz = groupChannels.length >= 2
                ? ChannelUtil.channelToFrequencyMhz(band, groupChannels[1]) - firstCenterMhz
                : channelWidth;
        int marginTicks = (int) Math.ceil(channelWidth * 0.75 / spacingMhz);
        p.xAxis.setLowerBound(firstCenterMhz - spacingMhz * marginTicks);
        p.xAxis.setUpperBound(lastCenterMhz + spacingMhz * marginTicks);
        p.xAxis.setTickUnit(spacingMhz);
        p.xAxis.setMinorTickCount(1);
        p.xAxis.setTickLabelFormatter(new StringConverter<>() {
            @Override
            public String toString(Number value) {
                double mhz = value.doubleValue();
                int channel = ChannelUtil.frequencyKhzToChannel((int) Math.round(mhz * 1000));
                boolean realChannel = Arrays.stream(groupChannels).anyMatch(c -> c == channel);
                return realChannel ? String.format("%.0f\nCh%d", mhz, channel) : String.format("%.0f", mhz);
            }

            @Override
            public Number fromString(String string) {
                return null;
            }
        });

        List<CurveEntry> curves = new ArrayList<>();
        for (int channel : group.channels()) {
            List<ChannelPlanner.Recommendation.ApContribution> contributions =
                    rec.perChannelContributions().getOrDefault(channel, List.of());
            // Only draw a curve for an AP that's *literally* on this channel (its own reported
            // primary channel, i.e. ApContribution.homeChannel() - NOT weight == 1.0: a wide
            // HT40/VHT80/160 AP's effective center can differ from its primary channel, so its
            // peak weight may land on a channel it isn't actually reporting as home). ChannelPlanner
            // also records a reduced-weight "contribution" from each nearby *overlapping* channel
            // for scoring purposes (e.g. an AP on channel 1 also nudges channels 2-4's congestion
            // score, since their 22MHz-wide channels overlap) - that's correct for the score, but
            // drawing a full occupancy curve on every one of those nearby channels too made it look
            // like the same AP was broadcasting on all of them, when really only one channel is
            // where it actually sits. The hover breakdown (showChannelBreakdown) still surfaces
            // those secondary contributions on hover - this filter only affects which entries get
            // their own drawn curve.
            List<ChannelPlanner.Recommendation.ApContribution> onThisChannel = contributions.stream()
                    .filter(c -> c.homeChannel() == channel)
                    .toList();
            if (onThisChannel.isEmpty()) {
                continue;
            }
            double centerMhz = ChannelUtil.channelToFrequencyMhz(band, channel);
            // Tallest first, so stacked labels on a shared channel read strongest-to-weakest,
            // matching the hover breakdown's own ordering.
            List<ChannelPlanner.Recommendation.ApContribution> sorted = onThisChannel.stream()
                    .sorted(Comparator.comparingDouble(ChannelPlanner.Recommendation.ApContribution::contribution).reversed())
                    .toList();
            for (ChannelPlanner.Recommendation.ApContribution c : sorted) {
                XYChart.Series<Number, Number> series = new XYChart.Series<>();
                series.setName(c.bssid());
                // Draw at this AP's own detected width (c.widthMhz()), not the fixed per-band
                // default, so a wide HT40/VHT80/160 AP's curve visually reflects the same wider
                // occupancy its congestion score now accounts for - falling back to the fixed
                // default only widens the drawing for the (rare) case a detected width is somehow
                // narrower than one plain channel, which would otherwise draw a sliver too thin to see.
                double curveWidthMhz = Math.max(channelWidth, c.widthMhz() / 20.0 * channelWidth);
                for (double[] pt : trapezoidPoints(centerMhz, curveWidthMhz, c.contribution())) {
                    series.getData().add(new XYChart.Data<>(pt[0], pt[1]));
                }
                p.chart.getData().add(series);
                curves.add(new CurveEntry(c.bssid(), c.ssid(), channel, centerMhz, c.contribution(), series));
            }
        }

        // Coloring/labels need the series' render Node, and the boundary/recommendation
        // annotations need final axis pixel positions - neither exists/settles after only one
        // deferred layout pass (same timing caveat as DashboardView's spectrum peak labels), so
        // two nested runLater calls.
        Platform.runLater(() -> Platform.runLater(() -> {
            if (p.generation != myGeneration) {
                // A newer refresh() already rebuilt this same panel slot (rapid band switches, or
                // a poll tick landing mid-build) before this build's layout pass caught up - its
                // clear step already ran, and appending this stale build's labels/dividers now
                // would just leave orphaned nodes behind until some future refresh happens to
                // clear them. Simplest correct fix: a superseded build contributes nothing.
                return;
            }
            for (CurveEntry curve : curves) {
                String hex = CategoricalColorPalette.toWeb(colorPalette.colorFor(curve.bssid()));
                if (curve.series().getNode() instanceof Group seriesGroup) {
                    Node line = seriesGroup.lookup(".chart-series-area-line");
                    Node fill = seriesGroup.lookup(".chart-series-area-fill");
                    if (line != null) {
                        line.getStyleClass().add("channel-occupancy-line");
                        line.setStyle("-fx-stroke: " + hex + ";");
                    }
                    if (fill != null) {
                        fill.getStyleClass().add("channel-occupancy-fill");
                        fill.setStyle("-fx-fill: " + hex + ";");
                    }
                    int channel = curve.channel();
                    seriesGroup.setOnMouseEntered(ev -> {
                        hoveredChannel = channel;
                        showChannelBreakdown(channel, rec);
                    });
                    seriesGroup.setOnMouseExited(ev -> {
                        hoveredChannel = null;
                        showHoverPlaceholder();
                    });
                }
            }

            // One label per *channel*, not per curve: trying to stack separate same-channel
            // labels at a small vertical offset from each other (an earlier version of this code)
            // still visually merged into unreadable overlapping text whenever two APs on the same
            // channel had similar contribution scores (a common case - e.g. two SSIDs off the same
            // physical router) since their curves - and therefore their natural label positions -
            // sit at nearly the same height. Joining every SSID on a channel into one combined
            // label at the tallest curve's position sidesteps the overlap problem entirely instead
            // of trying to out-guess it with a bigger fixed offset.
            Map<Integer, List<CurveEntry>> curvesByChannel = new LinkedHashMap<>();
            for (CurveEntry curve : curves) {
                curvesByChannel.computeIfAbsent(curve.channel(), k -> new ArrayList<>()).add(curve);
            }
            for (List<CurveEntry> sameChannel : curvesByChannel.values()) {
                // curves (and therefore each sameChannel group) was built tallest-first per
                // channel, so the group's first entry is where the combined label should anchor.
                CurveEntry tallest = sameChannel.get(0);
                String combinedText = sameChannel.stream()
                        .map(c -> c.ssid().isEmpty() ? "<hidden>" : c.ssid())
                        .distinct()
                        .collect(Collectors.joining(" / "));
                double px = p.xAxis.getDisplayPosition(tallest.centerMhz());
                double py = p.yAxis.getDisplayPosition(tallest.contribution());
                Text label = new Text(combinedText);
                label.setFill(Color.web(CategoricalColorPalette.toWeb(colorPalette.colorFor(tallest.bssid()))));
                label.getStyleClass().add("chart-annotation-label");
                label.setX(px - 14);
                label.setY(Math.max(10, py - 8));
                label.setMouseTransparent(true);
                p.chart.addAnnotation(label);
                p.annotations.add(label);
            }

            Node plotBg = p.chart.lookup(".chart-plot-background");
            double plotHeight = plotBg instanceof Region r ? r.getHeight() : 300;
            for (double boundaryMhz : new double[]{group.startMhz(), group.endMhz()}) {
                double px = p.xAxis.getDisplayPosition(boundaryMhz);
                Line divider = new Line(px, 0, px, plotHeight);
                divider.getStyleClass().add("subband-divider");
                divider.setMouseTransparent(true);
                p.chart.addAnnotation(divider);
                p.annotations.add(divider);
            }

            if (Arrays.stream(group.channels()).anyMatch(c -> c == rec.channel())) {
                double px = p.xAxis.getDisplayPosition(ChannelUtil.channelToFrequencyMhz(band, rec.channel()));
                double py = p.yAxis.getDisplayPosition(0);
                Text recLabel = new Text(Messages.get("channelPlanning.recommendedLabel"));
                recLabel.setFill(Color.web("#e4e8ec"));
                recLabel.getStyleClass().add("chart-annotation-label");
                recLabel.setX(px - 10);
                recLabel.setY(Math.max(10, py - 8));
                recLabel.setMouseTransparent(true);
                p.chart.addAnnotation(recLabel);
                p.annotations.add(recLabel);
            }
        }));
    }

    /**
     * Samples a flat-topped "mesa" curve for one channel: 0 at the far edges, rising through a
     * smootherstep-eased shoulder to a flat plateau at {@code height} across the middle, and back
     * down - the same shape the reference channel-plan diagrams use for a fixed-bandwidth channel
     * (unlike a Gaussian bell, which would misleadingly suggest the channel's edges are fuzzy
     * rather than a hard regulatory boundary).
     */
    private static List<double[]> trapezoidPoints(double centerMhz, double channelWidthMhz, double height) {
        double halfWidth = channelWidthMhz / 2;
        double shoulder = channelWidthMhz * 0.18;
        double flatHalfWidth = halfWidth - shoulder;
        double edgeMargin = channelWidthMhz * 0.15;
        int steps = 40;
        List<double[]> points = new ArrayList<>(steps + 1);
        double start = centerMhz - halfWidth - edgeMargin;
        double end = centerMhz + halfWidth + edgeMargin;
        for (int i = 0; i <= steps; i++) {
            double f = start + (end - start) * i / steps;
            double d = Math.abs(f - centerMhz);
            double y;
            if (d <= flatHalfWidth) {
                y = height;
            } else if (d <= halfWidth) {
                double t = (d - flatHalfWidth) / shoulder;
                double eased = t * t * t * (t * (t * 6 - 15) + 10); // smootherstep
                y = height * (1 - eased);
            } else {
                y = 0;
            }
            points.add(new double[]{f, y});
        }
        return points;
    }

    private void showChannelBreakdown(int channel, ChannelPlanner.Recommendation rec) {
        hoverHeader.setText(Messages.get("channelPlanning.hover.channelHeader", channel));
        List<ChannelPlanner.Recommendation.ApContribution> contributions =
                rec.perChannelContributions().getOrDefault(channel, List.of());
        hoverEntriesBox.getChildren().clear();
        contributions.stream()
                .sorted(Comparator.comparingDouble(ChannelPlanner.Recommendation.ApContribution::contribution).reversed())
                .forEach(c -> {
                    Rectangle swatch = new Rectangle(10, 10, colorPalette.colorFor(c.bssid()));
                    String ssidLabel = c.ssid().isEmpty() ? "<hidden>" : c.ssid();
                    String shortMac = c.bssid().length() >= 5 ? c.bssid().substring(c.bssid().length() - 5) : c.bssid();
                    String utilText = c.utilizationPercent() == null ? ""
                            : Messages.get("channelPlanning.hover.utilizationSuffix", c.utilizationPercent());
                    Label row = new Label(Messages.get("channelPlanning.hover.row", ssidLabel, shortMac, c.contribution(), utilText));
                    hoverEntriesBox.getChildren().add(new HBox(6, swatch, row));
                });
    }
}

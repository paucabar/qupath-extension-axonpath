package qupath.ext.axonpath.ui;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import org.controlsfx.control.CheckListView;
import org.controlsfx.control.SegmentedButton;
import org.controlsfx.dialog.ProgressDialog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.ext.axonpath.core.ExportTools;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Dialog for exporting AxonPath measurements to a TSV file.
 * Supports Fibre, Axon, and InnerCylinder classes; one class per export.
 */
public class ExportDialog {

    private static final Logger logger = LoggerFactory.getLogger(ExportDialog.class);

    private static final PathClass FIBRE_CLASS = PathClass.getInstance("Fibre");
    private static final PathClass AXON_CLASS = PathClass.getInstance("Axon");
    private static final PathClass INNER_CYLINDER_CLASS = PathClass.getInstance("InnerCylinder");

    private static final Map<String, String> SEPARATORS;
    static {
        SEPARATORS = new LinkedHashMap<>();
        SEPARATORS.put("Tab", "\t");
        SEPARATORS.put("Comma", ",");
        SEPARATORS.put("Semicolon", ";");
    }

    private static File lastDirectory = null;

    private ExportDialog() {}

    /**
     * Opens the export dialog as a modal window.
     *
     * @param owner   the owning Stage
     * @param project the current QuPath project
     */
    public static void show(Stage owner, Project<BufferedImage> project) {
        if (project == null) return;

        var images = project.getImageList();
        var entryMap = new LinkedHashMap<String, ProjectImageEntry<BufferedImage>>();
        for (var entry : images) entryMap.put(entry.getImageName(), entry);

        // ── Class selector ──────────────────────────────────────────────────
        var fibreBtn = new ToggleButton("Fibre");
        var axonBtn = new ToggleButton("Axon");
        var innerCylinderBtn = new ToggleButton("InnerCylinder");
        fibreBtn.setUserData(FIBRE_CLASS);
        axonBtn.setUserData(AXON_CLASS);
        innerCylinderBtn.setUserData(INNER_CYLINDER_CLASS);
        fibreBtn.setSelected(true);
        var classSegmented = new SegmentedButton(fibreBtn, axonBtn, innerCylinderBtn);

        // ── Image list ───────────────────────────────────────────────────────
        var imageListView = new CheckListView<String>();
        imageListView.getItems().addAll(entryMap.keySet());
        imageListView.setPrefHeight(160);

        var selectAllImagesBtn = new Button("Select All");
        var selectNoneImagesBtn = new Button("Select None");
        selectAllImagesBtn.setOnAction(e ->
                imageListView.getItems().forEach(item -> imageListView.getCheckModel().check(item)));
        selectNoneImagesBtn.setOnAction(e -> imageListView.getCheckModel().clearChecks());

        var imageButtonRow = new HBox(5, selectAllImagesBtn, selectNoneImagesBtn);
        var imagesLabel = new Label("Images:");
        var imageBox = new VBox(5, imagesLabel, imageListView, imageButtonRow);
        VBox.setVgrow(imageListView, Priority.ALWAYS);

        // ── Separator ────────────────────────────────────────────────────────
        var separatorCombo = new ComboBox<String>();
        separatorCombo.getItems().addAll(SEPARATORS.keySet());
        separatorCombo.getSelectionModel().selectFirst();
        var separatorRow = new HBox(10, new Label("Separator:"), separatorCombo);
        separatorRow.setAlignment(Pos.CENTER_LEFT);

        // ── Output path ──────────────────────────────────────────────────────
        var outputField = new TextField();
        outputField.setPromptText("Output file...");
        HBox.setHgrow(outputField, Priority.ALWAYS);
        var browseBtn = new Button("Browse...");
        var outputRow = new HBox(5, new Label("Output:"), outputField, browseBtn);
        outputRow.setAlignment(Pos.CENTER_LEFT);

        // ── Column list ──────────────────────────────────────────────────────
        var columnListView = new CheckListView<String>();
        columnListView.setPrefHeight(160);

        var populateIndicator = new ProgressIndicator(-1);
        populateIndicator.setPrefSize(18, 18);
        populateIndicator.setVisible(false);

        var populateBtn = new Button("Populate");
        var populateRow = new HBox(8, populateBtn, populateIndicator);
        populateRow.setAlignment(Pos.CENTER_LEFT);

        var selectAllColumnsBtn = new Button("All");
        var selectNoneColumnsBtn = new Button("None");
        selectAllColumnsBtn.setOnAction(e ->
                columnListView.getItems().forEach(item -> columnListView.getCheckModel().check(item)));
        selectNoneColumnsBtn.setOnAction(e -> columnListView.getCheckModel().clearChecks());

        var columnButtonRow = new HBox(5, selectAllColumnsBtn, selectNoneColumnsBtn);
        var columnsLabel = new Label("Columns:");
        var columnBox = new VBox(5, columnsLabel, populateRow, columnListView, columnButtonRow);
        VBox.setVgrow(columnListView, Priority.ALWAYS);

        // Clear column list on class change (columns are class-specific)
        for (var btn : classSegmented.getButtons()) {
            btn.selectedProperty().addListener((obs, oldV, newV) -> {
                if (Boolean.TRUE.equals(newV))
                    columnListView.getItems().clear();
            });
        }

        // ── Action buttons ───────────────────────────────────────────────────
        var cancelBtn = new Button("Cancel");
        var exportBtn = new Button("Export");
        exportBtn.setDefaultButton(true);
        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var actionRow = new HBox(10, spacer, cancelBtn, exportBtn);

        // ── Main layout ──────────────────────────────────────────────────────
        var mainBox = new VBox(12);
        mainBox.setPadding(new Insets(15));
        mainBox.getChildren().addAll(
                new Label("Class:"),
                classSegmented,
                imageBox,
                new Separator(),
                separatorRow,
                outputRow,
                new Separator(),
                columnBox,
                new Separator(),
                actionRow
        );
        VBox.setVgrow(imageBox, Priority.ALWAYS);
        VBox.setVgrow(columnBox, Priority.ALWAYS);

        // ── Stage ────────────────────────────────────────────────────────────
        var stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("AxonPath — Export Measurements");
        stage.setScene(new Scene(mainBox, 480, 600));
        stage.setMinWidth(400);
        stage.setMinHeight(500);

        // ── Event handlers ───────────────────────────────────────────────────
        cancelBtn.setOnAction(e -> stage.close());

        populateBtn.setOnAction(e -> {
            var checkedImages = new ArrayList<>(imageListView.getCheckModel().getCheckedItems()).stream()
                    .map(entryMap::get).filter(Objects::nonNull).toList();
            if (checkedImages.isEmpty()) {
                Dialogs.showWarningNotification("AxonPath", "Select at least one image to populate columns.");
                return;
            }
            var targetClass = getSelectedClass(classSegmented);
            populateBtn.setDisable(true);
            populateIndicator.setVisible(true);
            columnListView.getItems().clear();
            CompletableFuture.supplyAsync(() -> ExportTools.populateColumns(checkedImages, targetClass))
                    .thenAcceptAsync(cols -> {
                        columnListView.getItems().setAll(cols);
                        populateBtn.setDisable(false);
                        populateIndicator.setVisible(false);
                    }, Platform::runLater);
        });

        browseBtn.setOnAction(e -> {
            var fc = new FileChooser();
            fc.setTitle("Save measurements");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("TSV files", "*.tsv"));
            fc.setInitialFileName(getSelectedClass(classSegmented).getName() + "_measurements.tsv");
            if (lastDirectory != null && lastDirectory.exists())
                fc.setInitialDirectory(lastDirectory);
            var file = fc.showSaveDialog(stage);
            if (file != null) {
                outputField.setText(file.getAbsolutePath());
                lastDirectory = file.getParentFile();
            }
        });

        exportBtn.setOnAction(e -> {
            var checkedImageNames = new ArrayList<>(imageListView.getCheckModel().getCheckedItems());
            if (checkedImageNames.isEmpty()) {
                Dialogs.showWarningNotification("AxonPath", "Select at least one image to export.");
                return;
            }
            var selectedImages = checkedImageNames.stream()
                    .map(entryMap::get).filter(Objects::nonNull).toList();

            boolean populateUsed = !columnListView.getItems().isEmpty();
            final List<String> selectedColumns;
            if (!populateUsed) {
                selectedColumns = List.of();
            } else {
                var checked = new ArrayList<>(columnListView.getCheckModel().getCheckedItems());
                if (checked.isEmpty()) {
                    Dialogs.showWarningNotification("AxonPath",
                            "Select at least one column, or clear the column list to export all measurements.");
                    return;
                }
                selectedColumns = checked;
            }

            var outputPath = outputField.getText().trim();
            if (outputPath.isEmpty()) {
                Dialogs.showWarningNotification("AxonPath", "Specify an output file path.");
                return;
            }
            var outputFile = new File(outputPath);
            if (outputFile.getParentFile() != null)
                lastDirectory = outputFile.getParentFile();

            var sep = SEPARATORS.getOrDefault(separatorCombo.getValue(), "\t");
            var targetClass = getSelectedClass(classSegmented);

            var task = new Task<Void>() {
                @Override
                protected Void call() throws Exception {
                    ExportTools.exportMeasurements(
                            selectedImages, targetClass, selectedColumns, sep, outputFile,
                            (msg, p) -> { updateMessage(msg); updateProgress((long)(p * 100), 100); });
                    return null;
                }
            };

            var progressDialog = new ProgressDialog(task);
            progressDialog.initOwner(stage);
            progressDialog.setTitle("AxonPath");
            progressDialog.setHeaderText("Exporting " + targetClass.getName() + " measurements\u2026");
            progressDialog.setOnShown(ev -> progressDialog.getDialogPane().setGraphic(null));

            task.setOnSucceeded(ev -> {
                progressDialog.close();
                stage.close();
                Dialogs.showInfoNotification("AxonPath", "Exported to: " + outputFile.getAbsolutePath());
            });
            task.setOnFailed(ev -> {
                progressDialog.close();
                logger.error("Export failed", task.getException());
                Dialogs.showErrorMessage("AxonPath",
                        task.getException() != null ? task.getException().getMessage() : "Unexpected error during export.");
            });

            var thread = new Thread(task, "axonpath-export");
            thread.setDaemon(true);
            thread.start();
            progressDialog.showAndWait();
        });

        stage.showAndWait();
    }

    private static PathClass getSelectedClass(SegmentedButton segmented) {
        return segmented.getButtons().stream()
                .filter(ToggleButton::isSelected)
                .map(b -> (PathClass) b.getUserData())
                .findFirst()
                .orElse(FIBRE_CLASS);
    }
}

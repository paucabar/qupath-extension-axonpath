package qupath.ext.axonpath.ui;

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
import qupath.ext.axonpath.core.AnnotationExportTools;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;

/**
 * Dialog for exporting AxonPath annotation data from a QuPath project.
 * Supports two modes:
 * <ul>
 *   <li>Training — downsampled image tiles, semantic masks, and fibre instance labels</li>
 *   <li>Raw (GeoJSON) — full-resolution images and GeoJSON annotation files</li>
 * </ul>
 */
public class AnnotationExportDialog {

    private static final Logger logger = LoggerFactory.getLogger(AnnotationExportDialog.class);

    private static File lastDirectory = null;

    private AnnotationExportDialog() {}

    /**
     * Opens the annotation export dialog as a modal window.
     *
     * @param owner   the owning Stage
     * @param project the current QuPath project
     */
    public static void show(Stage owner, Project<BufferedImage> project) {
        if (project == null) return;

        var images = project.getImageList();
        var entryMap = new LinkedHashMap<String, ProjectImageEntry<BufferedImage>>();
        for (var entry : images) entryMap.put(entry.getImageName(), entry);

        // ── Mode selector ────────────────────────────────────────────────────
        var trainingBtn = new ToggleButton("Training");
        var rawBtn      = new ToggleButton("Raw (GeoJSON)");
        trainingBtn.setSelected(true);
        var modeSegmented = new SegmentedButton(trainingBtn, rawBtn);
        // SegmentedButton wraps a plain ToggleGroup, which allows clicking the
        // already-selected button to deselect it (ToggleButton.fire() just negates
        // isSelected()), leaving the group with no selection while the mode panel
        // stays visually unchanged. Re-select the previous toggle to prevent this.
        var modeToggleGroup = modeSegmented.getToggleGroup();
        modeToggleGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null) modeToggleGroup.selectToggle(oldToggle);
        });

        // ── Image list ───────────────────────────────────────────────────────
        var imageListView = new CheckListView<String>();
        imageListView.getItems().addAll(entryMap.keySet());
        imageListView.setPrefHeight(160);

        var selectAllBtn  = new Button("Select All");
        var selectNoneBtn = new Button("Select None");
        selectAllBtn.setOnAction(e ->
                imageListView.getItems().forEach(item -> imageListView.getCheckModel().check(item)));
        selectNoneBtn.setOnAction(e -> imageListView.getCheckModel().clearChecks());

        var imageButtonRow = new HBox(5, selectAllBtn, selectNoneBtn);
        var imageBox = new VBox(5, new Label("Images:"), imageListView, imageButtonRow);
        VBox.setVgrow(imageListView, Priority.ALWAYS);

        // ── Training-specific controls ───────────────────────────────────────
        var downsampleField = new TextField("1.0");
        downsampleField.setPrefWidth(80);
        var downsampleRow = new HBox(10, new Label("Downsample:"), downsampleField);
        downsampleRow.setAlignment(Pos.CENTER_LEFT);

        var trainingOutputField = new TextField();
        trainingOutputField.setPromptText("Output directory...");
        HBox.setHgrow(trainingOutputField, Priority.ALWAYS);
        var trainingBrowseBtn  = new Button("Browse...");
        var trainingOutputRow  = new HBox(5, new Label("Output:"), trainingOutputField, trainingBrowseBtn);
        trainingOutputRow.setAlignment(Pos.CENTER_LEFT);

        var trainingControls = new VBox(8, downsampleRow, trainingOutputRow);

        // ── Raw-specific controls ────────────────────────────────────────────
        var rawOutputField = new TextField();
        rawOutputField.setPromptText("Output directory...");
        HBox.setHgrow(rawOutputField, Priority.ALWAYS);
        var rawBrowseBtn  = new Button("Browse...");
        var rawOutputRow  = new HBox(5, new Label("Output:"), rawOutputField, rawBrowseBtn);
        rawOutputRow.setAlignment(Pos.CENTER_LEFT);

        var rawControls = new VBox(8, rawOutputRow);

        // ── Mode-specific container ──────────────────────────────────────────
        var modeBox = new VBox(trainingControls);
        trainingBtn.selectedProperty().addListener((obs, oldV, newV) -> {
            if (Boolean.TRUE.equals(newV))
                modeBox.getChildren().setAll(trainingControls);
        });
        rawBtn.selectedProperty().addListener((obs, oldV, newV) -> {
            if (Boolean.TRUE.equals(newV))
                modeBox.getChildren().setAll(rawControls);
        });

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
                new Label("Mode:"),
                modeSegmented,
                imageBox,
                new Separator(),
                modeBox,
                new Separator(),
                actionRow
        );
        VBox.setVgrow(imageBox, Priority.ALWAYS);

        // ── Stage ────────────────────────────────────────────────────────────
        var stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("AxonPath — Export Annotations");
        stage.setScene(new Scene(mainBox, 480, 500));
        stage.setMinWidth(400);
        stage.setMinHeight(400);

        // ── Event handlers ───────────────────────────────────────────────────
        cancelBtn.setOnAction(e -> stage.close());

        trainingBrowseBtn.setOnAction(e -> {
            var dir = promptDirectory(stage);
            if (dir != null) trainingOutputField.setText(dir.getAbsolutePath());
        });

        rawBrowseBtn.setOnAction(e -> {
            var dir = promptDirectory(stage);
            if (dir != null) rawOutputField.setText(dir.getAbsolutePath());
        });

        exportBtn.setOnAction(e -> {
            var checkedNames = new ArrayList<>(imageListView.getCheckModel().getCheckedItems());
            if (checkedNames.isEmpty()) {
                Dialogs.showWarningNotification("AxonPath", "Select at least one image to export.");
                return;
            }
            var selectedImages = checkedNames.stream()
                    .map(entryMap::get).filter(Objects::nonNull).toList();

            boolean isTraining = trainingBtn.isSelected();

            if (isTraining) {
                double downsample;
                try {
                    downsample = Double.parseDouble(downsampleField.getText().trim());
                    if (downsample <= 0) throw new NumberFormatException();
                } catch (NumberFormatException ex) {
                    Dialogs.showWarningNotification("AxonPath", "Downsample must be a positive number.");
                    return;
                }
                var outputPath = trainingOutputField.getText().trim();
                if (outputPath.isEmpty()) {
                    Dialogs.showWarningNotification("AxonPath", "Specify an output directory.");
                    return;
                }
                var outputDir = new File(outputPath);
                lastDirectory = outputDir;

                List<String> warnings = Collections.synchronizedList(new ArrayList<>());
                double finalDownsample = downsample;
                var task = new Task<Void>() {
                    @Override
                    protected Void call() throws Exception {
                        AnnotationExportTools.exportTraining(
                                selectedImages, outputDir, finalDownsample,
                                (msg, p) -> { updateMessage(msg); updateProgress((long)(p * 100), 100); },
                                warnings::add);
                        return null;
                    }
                };
                runExportTask(task, stage, "Exporting training data…", outputDir, warnings);

            } else {
                var outputPath = rawOutputField.getText().trim();
                if (outputPath.isEmpty()) {
                    Dialogs.showWarningNotification("AxonPath", "Specify an output directory.");
                    return;
                }
                var outputDir = new File(outputPath);
                lastDirectory = outputDir;

                var task = new Task<Void>() {
                    @Override
                    protected Void call() throws Exception {
                        AnnotationExportTools.exportRaw(
                                selectedImages, outputDir,
                                (msg, p) -> { updateMessage(msg); updateProgress((long)(p * 100), 100); });
                        return null;
                    }
                };
                runExportTask(task, stage, "Exporting raw images and annotations…", outputDir, List.of());
            }
        });

        stage.showAndWait();
    }

    private static void runExportTask(Task<Void> task, Stage dialogStage, String header,
                                      File outputDir, List<String> warnings) {
        var progressDialog = new ProgressDialog(task);
        progressDialog.initOwner(dialogStage);
        progressDialog.setTitle("AxonPath");
        progressDialog.setHeaderText(header);
        progressDialog.setOnShown(ev -> progressDialog.getDialogPane().setGraphic(null));

        task.setOnSucceeded(ev -> {
            progressDialog.close();
            dialogStage.close();
            Dialogs.showInfoNotification("AxonPath", "Exported to: " + outputDir.getAbsolutePath());
            if (!warnings.isEmpty()) {
                String msg = "No classified annotations found in " + warnings.size() + " image(s):\n"
                        + String.join("\n", warnings);
                Dialogs.showWarningNotification("AxonPath", msg);
            }
        });
        task.setOnFailed(ev -> {
            progressDialog.close();
            logger.error("Annotation export failed", task.getException());
            Dialogs.showErrorMessage("AxonPath",
                    task.getException() != null
                            ? task.getException().getMessage()
                            : "Unexpected error during export.");
        });

        var thread = new Thread(task, "axonpath-annotation-export");
        thread.setDaemon(true);
        thread.start();
        progressDialog.showAndWait();
    }

    private static File promptDirectory(Stage owner) {
        var dc = new DirectoryChooser();
        dc.setTitle("Select output directory");
        if (lastDirectory != null && lastDirectory.exists())
            dc.setInitialDirectory(lastDirectory);
        var dir = dc.showDialog(owner);
        if (dir != null) lastDirectory = dir;
        return dir;
    }
}

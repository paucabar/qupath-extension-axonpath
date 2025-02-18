package qupath.ext.aimseg.ui;

import java.util.List;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.ChoiceBox;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import org.controlsfx.control.SearchableComboBox;
import qupath.fx.dialogs.Dialogs;

import java.io.IOException;
import java.util.ResourceBundle;
import qupath.lib.scripting.QP;

/**
 * Controller for UI pane contained in interface.fxml
 */
public class AimSegController extends BorderPane {
    private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.aimseg.ui.strings");

    @FXML
    private SearchableComboBox<String> modelChoiceBox;
    @FXML
    private ChoiceBox<String> deviceChoiceBox;

    /**
     * Create a new instance of the interface controller.
     * @return a new instance of the interface controller
     * @throws IOException If reading the extension FXML files fails.
     */
    public static AimSegController createInstance() throws IOException {
        return new AimSegController();
    }

    /**
     * This method reads an FXML file. These are markup files containing the structure of a UI element.
     * <p>
     * Fields in this class tagged with <code>@FXML</code> correspond to UI elements, and methods tagged with <code>@FXML</code> are methods triggered by actions on the UI (e.g., mouse clicks).
     * <p>
     * We consider the use of FXML to be "best practice" for UI creation, as it separates logic from layout and enables easier use of CSS. However, it is not mandatory, and you could instead define the layout of the UI using code.
     * @throws IOException If the FXML can't be read successfully.
     */
    private AimSegController() throws IOException {
        var url = AimSegController.class.getResource("aimseg.fxml");
        FXMLLoader loader = new FXMLLoader(url, resources);
        loader.setRoot(this);
        loader.setController(this);
        loader.load();
        modelChoiceBox.getItems().addAll(List.of("Brightfield model", "EM model", "Local brightfield model", "Local EM model"));
        deviceChoiceBox.getItems().addAll(List.of("cpu", "gpu"));
    }

    @FXML
    private void runAimSeg() {
        runAimSegGroovy();
        Dialogs.showInfoNotification(
                "AimSeg extension",
                """
                        This method should run inference on the current image/annotation.
                        Probably it should retain a reference to the PathObjects it creates,
                        so that we can enable editing them later.
                        """
        );
    }

    @FXML
    private void toggleEditing() {
        Dialogs.showInfoNotification(
                "AimSeg extension",
                "This method should convert all AimSeg detections to annotations, or vice versa."
        );
    }

    @FXML
    private void recalculateHierarchy() {
        Dialogs.showInfoNotification(
                "AimSeg extension",
                "This method should re-do the hierarchy logic after editing."
        );
    }

    @FXML
    private void selectAllAnnotations() {
        QP.selectAnnotations();
    }

    @FXML
    private void selectAllDetections() {
        QP.selectDetections();
    }

}

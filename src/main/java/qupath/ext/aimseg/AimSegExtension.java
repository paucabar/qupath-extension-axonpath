package qupath.ext.aimseg;

import java.util.ResourceBundle;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.Property;
import javafx.scene.Scene;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.aimseg.ui.AimSegController;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.prefs.controlsfx.PropertyItemBuilder;
import qupath.fx.utils.FXUtils;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.GitHubProject;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.prefs.PathPrefs;

import java.io.IOException;


public class AimSegExtension implements QuPathExtension, GitHubProject {
	private static final ResourceBundle resources = ResourceBundle.getBundle("qupath.ext.aimseg.ui.strings");
	private static final Logger logger = LoggerFactory.getLogger(AimSegExtension.class);

	private static final String EXTENSION_NAME = resources.getString("extension.title");
	private static final String EXTENSION_DESCRIPTION = resources.getString("extension.description");
	private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.7.0");
	private static final GitHubRepo EXTENSION_REPOSITORY = GitHubRepo.create(
			EXTENSION_NAME, "paucabar", "AimSeg_QuPath_Extension");

	/**
	 * Flag whether the extension is already installed (might not be needed... but we'll do it anyway)
	 */
	private boolean isInstalled = false;

	/**
	 * A 'persistent preference' - showing how to create a property that is stored whenever QuPath is closed.
	 * This preference will be managed in the main QuPath GUI preferences window.
	 */
	private static final BooleanProperty enableExtensionProperty = PathPrefs.createPersistentPreference(
			"enableExtension", true);

	/**
	 * Another 'persistent preference'.
	 * This one will be managed using a GUI element created by the extension.
	 * We use {@link Property<Integer>} rather than {@link IntegerProperty}
	 * because of the type of GUI element we use to manage it.
	 */
	private static final Property<Integer> integerOption = PathPrefs.createPersistentPreference(
			"demo.num.option", 1).asObject();

	/**
	 * An example of how to expose persistent preferences to other classes in your extension.
	 * @return The persistent preference, so that it can be read or set somewhere else.
	 */
	public static Property<Integer> integerOptionProperty() {
		return integerOption;
	}

	/**
	 * Create a stage for the extension to display
	 */
	private Stage stage;

	@Override
	public void installExtension(QuPathGUI qupath) {
		if (isInstalled) {
			logger.debug("{} is already installed", getName());
			return;
		}
		isInstalled = true;
		addPreferenceToPane(qupath);
		addMenuItem(qupath);
	}

	private void handleStageHeightChange() {
		stage.sizeToScene();
		// This fixes a bug where the stage would migrate to the corner of a screen if it is
		// resized, hidden, then shown again
		if (stage.isShowing() && Double.isFinite(stage.getX()) && Double.isFinite(stage.getY()))
			FXUtils.retainWindowPosition(stage);
	}

	/**
	 * Demo showing how to add a persistent preference to the QuPath preferences pane.
	 * The preference will be in a section of the preference pane based on the
	 * category you set. The description is used as a tooltip.
	 * @param qupath The currently running QuPathGUI instance.
	 */
	private void addPreferenceToPane(QuPathGUI qupath) {
        var propertyItem = new PropertyItemBuilder<>(enableExtensionProperty, Boolean.class)
				.name("Enable")
				.category("Demo extension")
				.description("Enable the demo extension")
				.build();
		qupath.getPreferencePane()
				.getPropertySheet()
				.getItems()
				.add(propertyItem);
	}


	/**
	 * Demo showing how a new command can be added to a QuPath menu.
	 * @param qupath The QuPath GUI
	 */
	private void addMenuItem(QuPathGUI qupath) {
		var menu = qupath.getMenu("Extensions", false);
		MenuItem menuItem = new MenuItem("AimSeg");
		menuItem.setOnAction(e -> createStage());
		menuItem.disableProperty().bind(enableExtensionProperty.not());
		menu.getItems().add(menuItem);
	}

	/**
	 * Demo showing how to create a new stage with a JavaFX FXML interface.
	 */
	private void createStage() {
		if (stage == null) {
			try {
				stage = new Stage();
				var pane = AimSegController.createInstance();
				Scene scene = new Scene(new BorderPane(pane));
				pane.heightProperty().addListener((v, o, n) -> handleStageHeightChange());
				stage.initOwner(QuPathGUI.getInstance().getStage());
				stage.setTitle("AimSeg");
				stage.setScene(scene);
				stage.setResizable(false);
			} catch (IOException e) {
				Dialogs.showErrorMessage("Error", "GUI loading failed");
				logger.error("Unable to load extension interface FXML", e);
			}
		}
		stage.show();
	}


	@Override
	public String getName() {
		return EXTENSION_NAME;
	}

	@Override
	public String getDescription() {
		return EXTENSION_DESCRIPTION;
	}
	
	@Override
	public Version getQuPathVersion() {
		return EXTENSION_QUPATH_VERSION;
	}

	@Override
	public GitHubRepo getRepository() {
		return EXTENSION_REPOSITORY;
	}
}

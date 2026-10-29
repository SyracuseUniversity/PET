package com.atakmap.android.lingualinkledger.plugin;

import static com.atakmap.android.maps.MapView.getMapView;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.view.LayoutInflater;
import android.widget.EditText;
import android.widget.ArrayAdapter;

import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.gui.PluginSpinner;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.importfiles.ui.ImportManagerFileBrowser;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.importexport.send.SendDialog;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;


public class LinguaLinkLedger implements IPlugin {

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane mainPane;
    String TAG = "LinguaLinkLedger";
    View mainView;

    private Button statusButton;
    private Button helpButton;
    private Button createEditButton;
    private Button createEvidenceButton;
    private Button signEvidenceButton;
    private Button sendButton;
    private ToolFlows tools;

    final File TOOLS_DIR = FileSystemUtils.getItem("tools/lingualinkledger");

    public LinguaLinkLedger(IServiceController serviceController) {
        this.serviceController = serviceController;
        final PluginContextProvider ctxProvider = serviceController
                .getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }

        uiService = serviceController.getService(IHostUIService.class);

        toolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(
                        pluginContext.getResources().getDrawable(R.drawable.ic_launcher),
                        android.graphics.drawable.Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        showPane();
                    }
                })
                .build();
    }

    @Override
    public void onStart() {
        if (uiService == null) return;
        uiService.addToolbarItem(toolbarItem);
    }

    @Override
    public void onStop() {
        CameraCapture.cancelPending();
        PhotoSession.endCurrent();
        if (uiService == null) return;
        uiService.removeToolbarItem(toolbarItem);
    }

    private void showPane() {
        // PL-04: the pane is built once and reused, as in ATAK's plugin
        // template, so the visibility check below tests the pane on screen
        // rather than one just built
        if (mainPane == null) {
            mainView = PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null);
            setupButtonListeners();

            mainPane = new PaneBuilder(mainView)
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                    .build();
        }

        createDataPackageDirectories();

        if (!uiService.isPaneVisible(mainPane)) {
            uiService.showPane(mainPane, null);
        }
        loadRecords();
    }

    /**
     * Loads the records off the main thread (a rebuild reads every package)
     * and tells the user plainly if they were found damaged or missing and
     * rebuilt (LedgerStore; the user, Day 2 step 7).
     */
    private void loadRecords() {
        PetThreads.background("load records", () -> {
            String notice;
            try {
                PetFolders.ledger().ledger();
                notice = PetFolders.ledger().takeNotice();
            } catch (IOException e) {
                Log.w(TAG, "the records could not be read", e);
                notice = e.getMessage();
            }
            if (notice != null) {
                final String message = notice;
                PetThreads.onMain(() -> PetDialogs.showMessage(pluginContext, "Records on this phone", message));
            }
        });
    }

    private void setupButtonListeners() {
        statusButton = mainView.findViewById(R.id.status_button);
        helpButton = mainView.findViewById(R.id.help_button);
        createEditButton = mainView.findViewById(R.id.create_edit_dp_button);
        createEvidenceButton = mainView.findViewById(R.id.create_evidence_trace_button);
        signEvidenceButton = mainView.findViewById(R.id.add_stage_event_button);
        sendButton = mainView.findViewById(R.id.send_dp_button);

        statusButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showStatusDialog();
            }
        });

        // T1: the tools are secondary, closed until "Tools" is tapped
        final TextView toolsToggle = mainView.findViewById(R.id.tools_toggle);
        final View toolsSection = mainView.findViewById(R.id.tools_section);
        toolsToggle.setOnClickListener(v -> {
            boolean open = toolsSection.getVisibility() == View.VISIBLE;
            toolsSection.setVisibility(open ? View.GONE : View.VISIBLE);
            toolsToggle.setText(open ? "Tools  ▸" : "Tools  ▾");
        });

        helpButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showHelpDialog();
            }
        });

        // T1 (D54): photographs a label and records nothing
        final LookUpBatch lookUpBatch = new LookUpBatch(pluginContext);
        mainView.findViewById(R.id.look_up_batch_button).setOnClickListener(v -> lookUpBatch.start());

        // step 13: every batch this phone has recorded, then the tools' records (T3)
        final SavedBatches savedBatches = new SavedBatches(pluginContext);
        mainView.findViewById(R.id.saved_batches_button).setOnClickListener(v -> savedBatches.show());

        // the capture flow's stages (V1.7; Stored and Planting, step 11)
        final RecordStage recordStage = new RecordStage(pluginContext);
        tools = new ToolFlows(pluginContext);
        mainView.findViewById(R.id.stage_collecting_button).setOnClickListener(v -> recordStage.collecting());
        mainView.findViewById(R.id.stage_cleaning_button).setOnClickListener(v -> recordStage.cleaning());
        mainView.findViewById(R.id.stage_stored_button).setOnClickListener(v -> recordStage.stored());
        mainView.findViewById(R.id.stage_planting_button).setOnClickListener(v -> recordStage.planting());

        createEditButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                createOrEditDatapackage();
            }
        });

        // the tools' records (T2, T3, D44; D63): saved on the phone, nothing sent (D45)
        createEvidenceButton.setOnClickListener(v -> tools.createEvidenceTrace());
        signEvidenceButton.setOnClickListener(v -> tools.addStageEvent());

        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptForPackageToSend();
            }
        });
    }

    private void showHelpDialog() {
        PetDialogs.showMessage(pluginContext, "Help", pluginContext.getString(R.string.help_text));
    }

    // Status, which replaces Health Check (D53). App v1 has no gateway and
    // makes no network request (D45).
    private void showStatusDialog() {
        String saved;
        try {
            Ledger l = PetFolders.ledger().ledger();
            int tools = l.toolRecords().size();
            saved = "(" + l.eventCount() + " saved" + (tools == 0 ? "" : ", and " + tools
                    + (tools == 1 ? " record" : " records") + " from the tools") + ").";
        } catch (IOException e) {
            Log.w(TAG, "Status: the ledger could not be read", e);
            saved = "\n\n" + e.getMessage();
        }
        String message = "Gateway: not set up yet. Records are kept on this phone " + saved + "\n\n"
                + "This version sends nothing to any server or gateway. Send Data Package "
                + "uses ATAK's own sending.\n\n"
                + "App version: " + BuildConfig.VERSION_NAME;
        PetDialogs.showMessage(pluginContext, "Status", message);
    }

    private void createOrEditDatapackage() {
        showMetadataFormDialog();
    }

    // Create/Edit Data Package, fixed for v1 (D53; Day 2 step 8): a stage
    // picker (D44; ATAK's PluginSpinner, D46), the phone's own position by
    // V1.2's rules in place of the map's centre (PL-18), and the metadata
    // written as JSON (T5) under a safe file name (PL-33), its SHA-256
    // registered on the phone (T2). An AlertDialog against ATAK's activity
    // whose content scrolls (D46, PL-34).
    private void showMetadataFormDialog() {
        LayoutInflater inflater = (LayoutInflater) pluginContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View dialogView = inflater.inflate(R.layout.metadata_form, null);

        final EditText packageNameField = dialogView.findViewById(R.id.metadata_package_name);
        final EditText descriptionField = dialogView.findViewById(R.id.metadata_description);
        final EditText authorField = dialogView.findViewById(R.id.metadata_author);
        final PluginSpinner stageSpinner = dialogView.findViewById(R.id.metadata_stage);
        final TextView positionView = dialogView.findViewById(R.id.metadata_position);

        String[] stages = new String[Stage.values().length];
        for (int i = 0; i < stages.length; i++)
            stages[i] = Stage.values()[i].label;
        stageSpinner.setAdapter(new ArrayAdapter<>(pluginContext, android.R.layout.simple_spinner_item, stages));

        positionView.setText(positionText(PositionPolicy.check(AtakPosition.read(), System.currentTimeMillis())));

        final AlertDialog dialog = new AlertDialog.Builder(getMapView().getContext())
                .setView(dialogView)
                .create();

        Button cancelButton = dialogView.findViewById(R.id.metadata_cancel_button);
        Button createButton = dialogView.findViewById(R.id.metadata_create_button);

        cancelButton.setOnClickListener(v -> dialog.dismiss());

        createButton.setOnClickListener(v -> {
            final String packageName = packageNameField.getText().toString().trim();
            String description = descriptionField.getText().toString().trim();
            String author = authorField.getText().toString().trim();
            if (packageName.isEmpty()) {
                Toast.makeText(pluginContext, "Package name is required", Toast.LENGTH_SHORT).show();
                return;
            }

            // the position when Create is tapped; with none usable, nothing is written (V1.2)
            final long now = System.currentTimeMillis();
            PositionPolicy.Check position = PositionPolicy.check(AtakPosition.read(), now);
            positionView.setText(positionText(position));
            if (!position.usable()) {
                PetDialogs.showMessage(pluginContext, "Create/Edit Data Package",
                        "Nothing was created: this phone's position is not known.\n\n" + position.message);
                return;
            }
            Stage stage = Stage.values()[Math.max(0, stageSpinner.getSelectedItemPosition())];
            final Map<String, Object> metadata = ToolMetadata.build(packageName, description, author, stage,
                    position, now, BuildConfig.VERSION_NAME);

            dialog.dismiss();
            PetThreads.background("write metadata", () -> {
                File written = null;
                String failed = null;
                try {
                    written = PetFolders.ledger().writeToolMetadata(metadata, packageName, now);
                } catch (IOException e) {
                    Log.w(TAG, "Create/Edit Data Package: metadata not saved: " + e.getMessage()); // PL-14
                    failed = e.getMessage();
                }
                final File metadataFile = written;
                final String problem = failed;
                PetThreads.onMain(() -> {
                    if (metadataFile != null)
                        createDataPackageWithMetadata(packageName, metadataFile);
                    else
                        PetDialogs.showMessage(pluginContext, "Create/Edit Data Package",
                                "The metadata could not be saved on this phone (" + problem
                                        + "). Nothing was created.");
                });
            });
        });

        dialog.show();
    }

    /** The position as the form shows it: the summary, and any accuracy warning. */
    private static String positionText(PositionPolicy.Check c) {
        String s = c.summary();
        if (c.verdict == PositionPolicy.Verdict.OK_WITH_WARNING)
            s += "\n" + c.message;
        return s;
    }

    // D63: the plugin builds the package itself, as the SDK's sample does,
    // named from the form (PL-39), and says what was built (PL-38)
    private void createDataPackageWithMetadata(String packageName, final File metadataFile) {
        tools.buildDataPackage(packageName, metadataFile);
    }

    private void createDataPackageDirectories() {
        createDirectoryIfDoesNotExist(TOOLS_DIR);
        // PL-12: ATAK's data package folder, where Send Data Package starts
        createDirectoryIfDoesNotExist(PetFolders.atakDataPackages());
    }

    private void createDirectoryIfDoesNotExist(File directory) {
        if (!directory.exists()) {
            // PL-07: mkdirs, so missing parent folders are made too
            boolean created = directory.mkdirs();
            if (created) {
                Log.d(TAG, "Directory created: " + directory.getAbsolutePath());
            } else {
                Log.e(TAG, "Failed to create directory: " + directory.getAbsolutePath());
            }
        }
    }

    // Send Data Package: ATAK's own sending, to contacts or a TAK Server the
    // phone is set up for; not the PET gateway (D53). It works on any data
    // package (T4), shows its SHA-256 first, and writes no hash file, so a
    // file in the plugin's folders always means a saved record (PL-32).
    private void promptForPackageToSend() {
        // ATAK's file browser scrolls itself, so it is not put in a ScrollView
        final ImportManagerFileBrowser fileBrowser = ImportManagerFileBrowser.inflate(getMapView());
        fileBrowser.setTitle("Select a data package to send");
        fileBrowser.setStartDirectory(PetFolders.atakDataPackages().getAbsolutePath()); // PL-06
        fileBrowser.setExtensionTypes(new String[] {"zip"});
        AlertDialog.Builder alertBuilder = new AlertDialog.Builder(getMapView().getContext());
        alertBuilder.setView(fileBrowser);

        alertBuilder.setNegativeButton("Cancel", null);
        alertBuilder.setPositiveButton("Select", (dialog, num) -> {
            List<File> chosenFiles = fileBrowser.getSelectedFiles();
            if (chosenFiles.isEmpty()) {
                Toast.makeText(pluginContext, "No file selected", Toast.LENGTH_SHORT).show();
            } else {
                confirmSend(chosenFiles.get(0));
            }
        });
        final AlertDialog alertDialog = alertBuilder.create();
        fileBrowser.setAlertDialog(alertDialog);
        alertDialog.show();
    }

    private void confirmSend(final File dataPackage) {
        String fileHash = FileHasher.generateFileHash(dataPackage.getAbsolutePath());
        // PL-03: the hash is checked before it is used
        if (fileHash == null) {
            Log.w(TAG, "Send Data Package: could not read " + dataPackage.getAbsolutePath());
            PetDialogs.showMessage(pluginContext, "Send Data Package",
                    "Could not read " + dataPackage.getName() + ". Nothing was sent.");
            return;
        }
        String message = dataPackage.getName() + "\n\nSHA-256:\n" + fileHash
                + "\n\nATAK's Send dialog opens next. It sends to the contacts or TAK Server "
                + "you choose there. That is ATAK's own sending, not the PET gateway.";
        PetDialogs.builder(pluginContext, "Send Data Package", PetDialogs.text(pluginContext, message))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue",
                        (dialog, which) -> new SendDialog.Builder(getMapView()).addFile(dataPackage).show())
                .show();
    }

}
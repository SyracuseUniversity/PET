
package com.atakmap.android.lingualinkledger.plugin;

import static com.atakmap.android.maps.MapView.getMapView;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.PopupWindow;
import com.atak.plugins.impl.PluginContextProvider;
import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.importexport.ExportFilters;
import com.atakmap.android.importexport.Exportable;
import com.atakmap.android.importexport.FormatNotSupportedException;
import com.atakmap.android.importfiles.ui.ImportManagerFileBrowser;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.importexport.send.SendDialog;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;
import com.atakmap.android.missionpackage.export.MissionPackageExportMarshal;
import com.atakmap.android.missionpackage.export.MissionPackageExportWrapper;


public class LinguaLinkLedger implements IPlugin {

    IServiceController serviceController;
    Context pluginContext;
    IHostUIService uiService;
    ToolbarItem toolbarItem;
    Pane mainPane;
    String TAG = "LinguaLinkLedger";
    View mainView;

    private Button helpButton;
    private Button createEditButton;
    //private Button fingerprintButton;
    private Button sendButton;
    private String selectedMsvPath; // Variable to store the selected MSV path

    // Directory that the plugin will store all of its data in
    final File TOOLS_DIR = FileSystemUtils.getItem("tools/lingualinkledger");

    // ATAK's default directory fro newly created data packages
    final File NEW_PACKAGE_DIR = FileSystemUtils.getItem("tools/datapackage");

    // Directory where hashed data packages are stored
    final File HASHED_PACKAGE_DIR = new File(TOOLS_DIR, "hashed_packages");

    // Directory where sent data packages are stored
    final File SENT_PACKAGE_DIR = new File(TOOLS_DIR, "sent_packages");

    // Add this inside the LinguaLinkLedger class
    private class DataPackageMetadata {
        String packageName;
        String description;
        String author;
        double latitude;
        double longitude;

        public DataPackageMetadata(String packageName, String description, String author, double latitude, double longitude) {
            this.packageName = packageName;
            this.description = description;
            this.author = author;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        public boolean isValid() {
            return packageName != null && !packageName.trim().isEmpty();
        }
    }

    public LinguaLinkLedger(IServiceController serviceController) {
        this.serviceController = serviceController;
        final PluginContextProvider ctxProvider = serviceController
                .getService(PluginContextProvider.class);
        if (ctxProvider != null) {
            pluginContext = ctxProvider.getPluginContext();
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        }

        // obtain the UI service
        uiService = serviceController.getService(IHostUIService.class);

        // initialize the toolbar button for the plugin

        // create the button
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
        // the plugin is starting, add the button to the toolbar
        if (uiService == null)
            return;

        uiService.addToolbarItem(toolbarItem);
    }

    @Override
    public void onStop() {
        // the plugin is stopping, remove the button from the toolbar
        if (uiService == null)
            return;

        uiService.removeToolbarItem(toolbarItem);
    }

    private void showPane() {
        mainView = PluginLayoutInflater.inflate(pluginContext, R.layout.main_layout, null);

        // Set up button click listeners
        setupButtonListeners();

        mainPane = new PaneBuilder(mainView)
                // relative location is set to default; pane will switch location dependent on
                // current orientation of device screen
                .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                // pane will take up 50% of screen width in landscape mode
                .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.5D)
                // pane will take up 50% of screen height in portrait mode
                .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.5D)
                .build();

        createDataPackageDirectories();

        // if the plugin pane is not visible, show it!
        if (!uiService.isPaneVisible(mainPane)) {
            uiService.showPane(mainPane, null);
        }
    }

    private void setupButtonListeners() {

        helpButton = mainView.findViewById(R.id.help_button);
        createEditButton = mainView.findViewById(R.id.create_edit_dp_button);
        sendButton = mainView.findViewById(R.id.send_dp_button);

        helpButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showHelpDialog();
            }
        });

        createEditButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // create/edit data package functionality


                // Allow the user to create or edit a data package
                createOrEditDatapackage();
            }
        });

        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // send data package functionality
                PromptForMSVFileSelection(v.getContext());
                //packageToTak();
            }
        });
    }

    private void showHelpDialog() {
        LayoutInflater inflater = (LayoutInflater) pluginContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View popupView = inflater.inflate(R.layout.help_dialog_layout, null);

        // Set width to 80% of screen width, height to wrap content
        int width = (int) (mainView.getResources().getDisplayMetrics().widthPixels * 0.8);
        int height = WindowManager.LayoutParams.WRAP_CONTENT;
        final PopupWindow popupWindow = new PopupWindow(popupView, width, height, true);

        Button exitButton = popupView.findViewById(R.id.help_exit_button);
        exitButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                popupWindow.dismiss();
            }
        });

        popupWindow.showAtLocation(mainView.getRootView(), Gravity.CENTER, 0, 0);
    }

    private void createOrEditDatapackage() {
        showMetadataFormDialog();
    }

    private void showMetadataFormDialog() {
        LayoutInflater inflater = (LayoutInflater) pluginContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View dialogView = inflater.inflate(R.layout.metadata_form, null);

        // Get references to form fields
        final EditText packageNameField = dialogView.findViewById(R.id.metadata_package_name);
        final EditText descriptionField = dialogView.findViewById(R.id.metadata_description);
        final EditText authorField = dialogView.findViewById(R.id.metadata_author);
        final EditText latitudeField = dialogView.findViewById(R.id.metadata_latitude);
        final EditText longitudeField = dialogView.findViewById(R.id.metadata_longitude);

        // Get current map center and prefill lat/lon
        MapView mapView = MapView.getMapView();
        if (mapView != null) {
            com.atakmap.coremap.maps.coords.GeoPointMetaData centerPointMeta = mapView.getPoint();
            if (centerPointMeta != null) {
                com.atakmap.coremap.maps.coords.GeoPoint centerPoint = centerPointMeta.get();
                if (centerPoint != null) {
                    latitudeField.setText(String.format("%.6f", centerPoint.getLatitude()));
                    longitudeField.setText(String.format("%.6f", centerPoint.getLongitude()));
                }
            }
        }


        // Create dialog
        final AlertDialog dialog = new AlertDialog.Builder(getMapView().getContext())
                .setView(dialogView)
                .create();

        // Set up button listeners
        Button cancelButton = dialogView.findViewById(R.id.metadata_cancel_button);
        Button createButton = dialogView.findViewById(R.id.metadata_create_button);

        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });

        createButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Collect metadata
                String packageName = packageNameField.getText().toString().trim();
                String description = descriptionField.getText().toString().trim();
                String author = authorField.getText().toString().trim();

                // Get lat/lon values
                double latitude = 0.0;
                double longitude = 0.0;
                try {
                    latitude = Double.parseDouble(latitudeField.getText().toString().trim());
                    longitude = Double.parseDouble(longitudeField.getText().toString().trim());
                } catch (NumberFormatException e) {
                    Toast.makeText(pluginContext, "Invalid latitude or longitude", Toast.LENGTH_SHORT).show();
                    return;
                }

                // Validate required fields
                if (packageName.isEmpty()) {
                    Toast.makeText(pluginContext, "Package name is required", Toast.LENGTH_SHORT).show();
                    return;
                }

                // Create metadata object
                DataPackageMetadata metadata = new DataPackageMetadata(
                        packageName, description, author, latitude, longitude);

                // Close dialog
                dialog.dismiss();

                // Create the data package with metadata
                createDataPackageWithMetadata(metadata);
            }
        });

        dialog.show();
    }

    private void createDataPackageWithMetadata(DataPackageMetadata metadata) {
        MapView mapView = MapView.getMapView();

        try {
            // Create a metadata file
            File metadataFile = createMetadataFile(metadata);

            // Create the mission package export marshal
            MissionPackageExportMarshal missionPackageExportMarshal =
                    new MissionPackageExportMarshal(mapView.getContext(), true);

            List<Exportable> exportables = new ArrayList<>();

            // Add your metadata as part of the exportables
            exportables.add(new Exportable() {
                @Override
                public boolean isSupported(Class<?> aClass) {
                    return true;
                }

                @Override
                public Object toObjectOf(Class<?> aClass, ExportFilters exportFilters)
                        throws FormatNotSupportedException {
                    // Use the metadata in the mission package
                    return new MissionPackageExportWrapper(false, metadataFile.getAbsolutePath());
                }
            });

            missionPackageExportMarshal.execute(exportables);

            Toast.makeText(pluginContext,
                    "Data package '" + metadata.packageName + "' created successfully",
                    Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Log.e(TAG, "Error creating data package with metadata", e);
            Toast.makeText(pluginContext,
                    "Error creating data package: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private File createMetadataFile(DataPackageMetadata metadata) throws IOException {
        // Create a metadata file in the tools directory
        File metadataFile = new File(TOOLS_DIR, metadata.packageName + "_metadata.txt");

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(metadataFile))) {
            writer.write("Package Name: " + metadata.packageName + "\n");
            writer.write("Description: " + metadata.description + "\n");
            writer.write("Author: " + metadata.author + "\n");
            writer.write("Latitude: " + metadata.latitude + "\n");
            writer.write("Longitude: " + metadata.longitude + "\n");
            writer.write("Created: " + new java.util.Date().toString() + "\n");
        }

        Log.d(TAG, "Metadata file created: " + metadataFile.getAbsolutePath());
        return metadataFile;
    }

    // Create all of the date package storage directories that are used.
    private void createDataPackageDirectories() {
        createDirectoryIfDoesNotExist(TOOLS_DIR);
        createDirectoryIfDoesNotExist(NEW_PACKAGE_DIR);
        createDirectoryIfDoesNotExist(HASHED_PACKAGE_DIR);
        createDirectoryIfDoesNotExist(SENT_PACKAGE_DIR);
    }

    // If the directory doesn't exist, create it
    private void createDirectoryIfDoesNotExist(File directory) {
        // Check if the directory exists
        if (!directory.exists()) {
            // Create the directory
            boolean created = directory.mkdir(); // Use mkdirs() to create parent directories as well

            // Make sure the creation was successful
            if (created) {
                Log.w(TAG, "Directory created successfully: " + directory.getAbsolutePath());
            } else {
                Log.e(TAG, "Failed to create directory.");
            }
        }
    }

    private boolean moveFile(File sourceFile, File destinationDir) {
        try {
            // Ensure destination directory exists
            if (!destinationDir.exists()) {
                destinationDir.mkdirs();
            }

            // Define the destination file path (keep same filename)
            File destFile = new File(destinationDir, sourceFile.getName());

            // Create streams
            try (InputStream in = new FileInputStream(sourceFile);
                 OutputStream out = new FileOutputStream(destFile)) {

                byte[] buffer = new byte[4096];
                int length;
                while ((length = in.read(buffer)) > 0) {
                    out.write(buffer, 0, length);
                }
            }

            // Delete the original file
            return sourceFile.delete();

        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private void PromptForMSVFileSelection(Context context) {
        final String[] fileExtensions = {"zip"};
        final String startingDirectory = "/sdcard/atak/tools/datapackage";
        final ImportManagerFileBrowser fileBrowser = ImportManagerFileBrowser.inflate(getMapView());
        fileBrowser.setTitle("Select a Mission Package");
        fileBrowser.setStartDirectory(startingDirectory);
        fileBrowser.setExtensionTypes(fileExtensions);
        AlertDialog.Builder alertBuilder = new AlertDialog.Builder(getMapView().getContext());
        alertBuilder.setView(fileBrowser);

        alertBuilder.setNegativeButton("Cancel", null);
        alertBuilder.setPositiveButton("Select", (dialog, num) -> {
            List<File> chosenFiles = fileBrowser.getSelectedFiles();
            if (chosenFiles.isEmpty()) {
                Toast.makeText(pluginContext, "No Files Selected", Toast.LENGTH_SHORT).show();
            } else {
                selectedMsvPath = chosenFiles.get(0).getAbsolutePath();
                Toast.makeText(pluginContext, "Selected File: " + selectedMsvPath, Toast.LENGTH_SHORT).show();

                if (selectedMsvPath != null) {
                    String fileHash = generateFileHash(selectedMsvPath);
                    String fileName= fileHash.substring(fileHash.length() - 6) + ".txt.zip.dtg.sha256";
                    File file = new File(HASHED_PACKAGE_DIR, fileName);

                    try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                        writer.write(fileHash);
                        file.setWritable(false);
                        Log.w(TAG, fileName + "stored in hashed_packages");
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    if (fileHash != null) {
                        final PopupWindow hashPopup = showHashPopup(sendButton, fileHash); // Method to show the hash value in a popup
                        sendButton.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (hashPopup != null && hashPopup.isShowing()) {
                                    hashPopup.dismiss();
                                }
                                packageToTak();
                            }
                        }, 2000);
                    } else {
                        Toast.makeText(pluginContext, "Failed to generate hash", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(pluginContext, "No file selected", Toast.LENGTH_SHORT).show();
                }
            }
        });
        final AlertDialog alertDialog = alertBuilder.create();
        fileBrowser.setAlertDialog(alertDialog);
        alertDialog.show();
    }


    private PopupWindow showHashPopup(View anchorView, String hashValue) {
        LayoutInflater inflater = (LayoutInflater) pluginContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View popupView = inflater.inflate(R.layout.popup_hash_value, null);

        TextView hashTextView = popupView.findViewById(R.id.hash_value_text);
        hashTextView.setText(hashValue);

        final PopupWindow popupWindow = new PopupWindow(popupView,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true);

        popupWindow.showAtLocation(anchorView.getRootView(), Gravity.CENTER, 0, 0);
        return popupWindow;
    }

    private String generateFileHash(String filePath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            InputStream fis = new FileInputStream(filePath);
            byte[] buffer = new byte[1024];
            int bytesRead;

            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            fis.close();

            byte[] hashBytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private void packageToTak() {
        if (selectedMsvPath != null) {
            File msvFile = new File(selectedMsvPath);
            new SendDialog.Builder(getMapView()).addFile(msvFile).show();
        } else {
            Toast.makeText(pluginContext, "No MSV file selected", Toast.LENGTH_SHORT).show();
        }
    }
}
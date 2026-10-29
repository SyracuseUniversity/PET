package com.atakmap.android.lingualinkledger.plugin;

import static com.atakmap.android.maps.MapView.getMapView;

import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.atakmap.android.gui.PluginSpinner;
import com.atakmap.android.importexport.ImportExportMapComponent;
import com.atakmap.android.missionpackage.file.MissionPackageBuilder;
import com.atakmap.android.missionpackage.file.MissionPackageManifest;
import com.atakmap.android.util.ResUtils;
import com.atakmap.app.ATAKApplication;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import gov.tak.api.importfiles.ImportMissionPackageResolver;
import gov.tak.api.importfiles.ImportResolver;

/**
 * The tools beside the capture flow (D53; Day 3 step 12, D63):
 *
 *   Create/Edit Data Package's package: built here, as the SDK's
 *     helloworld sample builds one (a MissionPackageManifest named from
 *     the form, MissionPackageBuilder, then ATAK's own importer so it is
 *     listed in Data Packages), around the metadata file the plugin wrote
 *     and registered (T2). It is built in a staging folder outside ATAK's
 *     data packages, which the importer copies it into: the importer
 *     deletes whatever is at its destination (PL-42). No New/Existing
 *     dialog, so a PET package always holds that file; it is named from
 *     the form (PL-39); and the message says what was built, after the
 *     copy in ATAK's folder is checked (PL-38, PL-42).
 *   Create Evidence Trace: only a data package holding a registered
 *     metadata file is offered (T2, R4); it is hashed as stored and a
 *     record saved on the phone; a package is traced once (ToolRecords).
 *   Add Stage Event (D44): on one trace, a stage from ATAK's PluginSpinner
 *     (PL-35), a fresh position by V1.2's rules (with none usable, nothing
 *     is saved), optional typed remarks; saved on the phone.
 *
 * Nothing is sent (D45). Every dialog is built against ATAK's activity and
 * scrolls (D46, PL-34); file and ledger work is off the main thread, and
 * nothing thrown there can close ATAK (PetThreads).
 */
final class ToolFlows {

    private static final String TAG = "LinguaLinkLedger.Tools";
    static final String EDIT = "Create/Edit Data Package";
    static final String TRACE = "Create Evidence Trace";
    static final String STAGE = "Add Stage Event";

    private final Context pluginContext;

    ToolFlows(Context pluginContext) {
        this.pluginContext = pluginContext;
    }

    // --- Create/Edit Data Package: the package around the metadata file ----------

    /**
     * Builds the data package around a registered metadata file (on the main thread, as the sample does)
     * in the staging folder, then has ATAK's importer copy it into its data packages. ATAK's importer
     * deletes whatever is at its destination, so the package is never built there (PL-42).
     */
    void buildDataPackage(final String packageName, final File metadataFile) {
        final PackageStaging plan;
        File staged = null;
        try {
            File dir = PetFolders.atakDataPackages();
            if (!dir.isDirectory() && !dir.mkdirs())
                throw new IOException("could not make " + dir);
            File staging = stagingFolder();
            PackageStaging.sweep(staging);
            plan = PackageStaging.plan(staging, dir, packageName);
            staged = plan.staged;
            String name = plan.landed.getName().substring(0, plan.landed.getName().length() - ".zip".length());
            MissionPackageManifest mpm = new MissionPackageManifest();
            mpm.setName(name);
            mpm.setPath(plan.staged.getAbsolutePath());
            if (!mpm.addFile(metadataFile, null))
                throw new IOException("ATAK would not add " + metadataFile.getName() + " to it");
            String path = new MissionPackageBuilder(null, mpm, getMapView().getRootGroup()).build();
            if (path == null || !plan.staged.isFile())
                throw new IOException("ATAK's package builder made no file");
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "the data package could not be built", e);
            if (staged != null)
                CameraCapture.delete(staged); // the staging folder is left empty
            message(EDIT, "The metadata file was saved on this phone (" + metadataFile.getName()
                    + "), but no data package was built: " + e.getMessage() + ".");
            return;
        }
        final ImportResolver importer;
        try {
            importer = missionPackageImporter();
        } catch (RuntimeException e) {
            Log.w(TAG, "no importer for the data package", e);
            CameraCapture.delete(plan.staged);
            message(EDIT, notAdded(metadataFile, "ATAK could not be asked to add it (" + e + ")."));
            return;
        }
        final AlertDialog wait = waiting(EDIT, "Adding " + plan.landed.getName() + " to ATAK's data packages...");
        PetThreads.background("list data package", () -> {
            String problem = null;
            try {
                if (!importer.beginImport(plan.staged, EnumSet.of(ImportResolver.SortFlags.IMPORT_COPY)))
                    problem = "ATAK did not add it to its data packages.";
                else if (!plan.landed.isFile())
                    problem = "ATAK did not copy it into its data packages (atak/tools/datapackage).";
                // T2, checked on the copy ATAK holds, after the import (PL-42; PL-38's lesson)
                else if (ToolRecords.qualifies(plan.landed, PetFolders.ledger().ledger()) == null)
                    problem = "The copy in ATAK's data packages does not hold the registered metadata file, so "
                            + "Create Evidence Trace will not offer it.";
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "adding the data package failed: " + e.getMessage()); // PL-14: one line
                problem = "ATAK did not add it to its data packages (" + e.getMessage() + ").";
            } finally {
                CameraCapture.delete(plan.staged); // the staging folder is left empty
            }
            final String p = problem;
            PetThreads.onMain(() -> {
                wait.dismiss();
                message(EDIT, p == null ? built(plan.landed, metadataFile) : notAdded(metadataFile, p));
            });
        });
    }

    /** ATAK's external cache, beside the photos' folder (D55); outside ATAK's data packages (PL-42). */
    private static File stagingFolder() throws IOException {
        return new File(CameraCapture.photoRoot().getParentFile(), PackageStaging.FOLDER);
    }

    private static String built(File landed, File metadataFile) {
        return "Data package built: " + landed.getName() + ", in ATAK's data packages (atak/tools/datapackage)."
                + "\n\nIt holds the metadata file " + metadataFile.getName() + ", whose SHA-256 is kept on "
                + "this phone, so Create Evidence Trace can trace it.";
    }

    private static String notAdded(File metadataFile, String problem) {
        return "The metadata file was saved on this phone (" + metadataFile.getName() + "), but no data package "
                + "is in ATAK's data packages: " + problem;
    }

    /** ATAK's importer for data packages, made as the SDK's helloworld sample makes it. */
    private static ImportResolver missionPackageImporter() {
        Context aCtx = ATAKApplication.getCurrentActivity();
        File mpDestinationDir = FileSystemUtils.getItem(FileSystemUtils.TOOL_DATA_DIRECTORY + File.separatorChar
                + aCtx.getString(com.atakmap.app.R.string.mission_package_folder));
        ImportMissionPackageResolver importer = new ImportMissionPackageResolver.ImportMissionV1PackageResolver(
                mpDestinationDir, aCtx.getString(com.atakmap.app.R.string.mission_package_name),
                ResUtils.getDrawable(aCtx, com.atakmap.app.R.drawable.missionpackage_icon), false);
        ImportExportMapComponent.getInstance().initImportResolver(importer);
        return importer;
    }

    // --- Create Evidence Trace ------------------------------------------------------

    void createEvidenceTrace() {
        final AlertDialog wait = waiting(TRACE, "Looking for data packages built with " + EDIT + "...");
        PetThreads.background("find packages to trace", () -> {
            List<ToolRecords.Qualifying> found = null;
            String problem = null;
            try {
                found = ToolRecords.qualifying(PetFolders.atakDataPackages(), PetFolders.ledger().ledger());
            } catch (IOException e) {
                problem = e.getMessage();
            }
            final List<ToolRecords.Qualifying> q = found;
            final String p = problem;
            PetThreads.onMain(() -> {
                wait.dismiss();
                if (p != null) {
                    message(TRACE, p);
                } else if (q.isEmpty()) {
                    message(TRACE, "No data package built with " + EDIT + " was found in ATAK's data "
                            + "packages.\n\n" + TRACE + " takes only a package the plugin built, holding the "
                            + "metadata file it wrote, so that no other file can be traced. Build one with "
                            + EDIT + " first.");
                } else {
                    String[] labels = new String[q.size()];
                    for (int i = 0; i < labels.length; i++)
                        labels[i] = label(q.get(i));
                    new AlertDialog.Builder(getMapView().getContext())
                            .setTitle("Choose a data package to trace")
                            .setItems(labels, (d, which) -> trace(q.get(which)))
                            .setNegativeButton("Cancel", null)
                            .show();
                }
            });
        });
    }

    private static String label(ToolRecords.Qualifying q) {
        return q.file.getName() + "\n" + String.format(Locale.US, "%,d bytes", q.file.length())
                + ", stage " + q.metadata.get("stage") + ", " + UtcTime.format(q.file.lastModified());
    }

    private void trace(final ToolRecords.Qualifying q) {
        final AlertDialog wait = waiting(TRACE, "Hashing " + q.file.getName() + "...");
        PetThreads.background("trace", () -> {
            String text;
            try {
                String sha256 = FileHasher.generateFileHash(q.file.getAbsolutePath());
                if (sha256 == null)
                    throw new IOException("could not read " + q.file.getName());
                String why = ToolRecords.refuseTrace(PetFolders.ledger().ledger(), sha256);
                if (why != null) {
                    text = "Nothing was saved. " + why;
                } else {
                    Map<String, Object> r = PetFolders.ledger().recordTool(ToolRecords.trace(q, sha256,
                            q.file.length(), System.currentTimeMillis(), BuildConfig.VERSION_NAME));
                    text = savedText(r);
                }
            } catch (Ledger.Refused e) {
                text = "Nothing was saved. " + e.getMessage();
            } catch (IOException e) {
                Log.w(TAG, "trace not saved: " + e.getMessage()); // PL-14: one line
                text = "The trace could not be saved on this phone (" + e.getMessage() + ").";
            }
            final String t = text;
            PetThreads.onMain(() -> {
                wait.dismiss();
                message(TRACE, t);
            });
        });
    }

    // --- Add Stage Event --------------------------------------------------------------

    void addStageEvent() {
        PetThreads.background("find traces", () -> {
            List<Map<String, Object>> found = null;
            String problem = null;
            try {
                found = PetFolders.ledger().ledger().traces();
            } catch (IOException e) {
                problem = e.getMessage();
            }
            final List<Map<String, Object>> traces = found;
            final String p = problem;
            PetThreads.onMain(() -> {
                if (p != null) {
                    message(STAGE, p);
                } else if (traces.isEmpty()) {
                    message(STAGE, "No evidence traces are saved on this phone yet. " + STAGE + " adds a "
                            + "stage to one: use " + TRACE + " first.");
                } else {
                    String[] labels = new String[traces.size()];
                    for (int i = 0; i < labels.length; i++) {
                        Map<String, Object> t = traces.get(i);
                        labels[i] = "Trace " + t.get("tool_seq") + ": " + t.get("package_file") + "\nstage "
                                + t.get("stage") + ", traced " + t.get("traced_utc");
                    }
                    new AlertDialog.Builder(getMapView().getContext())
                            .setTitle("Choose an evidence trace")
                            .setItems(labels, (d, which) -> stageForm(traces.get(which)))
                            .setNegativeButton("Cancel", null)
                            .show();
                }
            });
        });
    }

    private void stageForm(final Map<String, Object> trace) {
        LayoutInflater inflater = (LayoutInflater) pluginContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View view = inflater.inflate(R.layout.add_stage_event_form, null);
        TextView summary = view.findViewById(R.id.stage_event_trace);
        final PluginSpinner stageSpinner = view.findViewById(R.id.stage_event_stage);
        final TextView positionView = view.findViewById(R.id.stage_event_position);
        final EditText remarksField = view.findViewById(R.id.stage_event_remarks);

        summary.setText("Trace " + trace.get("tool_seq") + ": " + trace.get("package_file") + "\nSHA-256: "
                + trace.get("package_sha256"));
        String[] stages = new String[Stage.values().length];
        for (int i = 0; i < stages.length; i++)
            stages[i] = Stage.values()[i].label;
        stageSpinner.setAdapter(new ArrayAdapter<>(pluginContext, android.R.layout.simple_spinner_item, stages));
        positionView.setText(positionText(PositionPolicy.check(AtakPosition.read(), System.currentTimeMillis())));

        final AlertDialog dialog = new AlertDialog.Builder(getMapView().getContext()).setView(view).create();
        Button cancel = view.findViewById(R.id.stage_event_cancel);
        Button save = view.findViewById(R.id.stage_event_save);
        cancel.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> {
            // the position when Save is tapped; with none usable, nothing is saved (V1.2, S6)
            final long now = System.currentTimeMillis();
            final PositionPolicy.Check position = PositionPolicy.check(AtakPosition.read(), now);
            positionView.setText(positionText(position));
            if (!position.usable()) {
                message(STAGE, "Nothing was saved: this phone's position is not known.\n\n" + position.message);
                return;
            }
            final Stage stage = Stage.values()[Math.max(0, stageSpinner.getSelectedItemPosition())];
            final String remarks = remarksField.getText().toString();
            dialog.dismiss();
            final AlertDialog wait = waiting(STAGE, "Saving on this phone...");
            PetThreads.background("save stage event", () -> {
                String text;
                try {
                    Map<String, Object> r = PetFolders.ledger().recordTool(ToolRecords.stageEvent(trace, stage,
                            position, remarks, now, BuildConfig.VERSION_NAME));
                    text = savedText(r);
                } catch (Ledger.Refused e) {
                    text = "Nothing was saved. " + e.getMessage();
                } catch (IOException e) {
                    Log.w(TAG, "stage event not saved: " + e.getMessage()); // PL-14: one line
                    text = "The stage event could not be saved on this phone (" + e.getMessage() + ").";
                }
                final String t = text;
                PetThreads.onMain(() -> {
                    wait.dismiss();
                    message(STAGE, t);
                });
            });
        });
        dialog.show();
    }

    // --- shared ---------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static String savedText(Map<String, Object> r) {
        StringBuilder sb = new StringBuilder("Saved on this phone. It will be sent when a gateway is connected.\n\n");
        Map<String, Object> pos = (Map<String, Object>) r.get("position");
        if (ToolRecords.KIND_TRACE.equals(r.get("kind"))) {
            sb.append("Tool record ").append(r.get("tool_seq")).append(": evidence trace");
            sb.append("\nPackage: ").append(r.get("package_file"));
            sb.append("\nSHA-256: ").append(r.get("package_sha256"));
            sb.append("\nStage, from its metadata: ").append(r.get("stage"));
        } else {
            sb.append("Tool record ").append(r.get("tool_seq")).append(": ").append(r.get("stage"))
                    .append(", on trace ").append(r.get("trace_tool_seq"));
            if (r.get("remarks") != null)
                sb.append("\nRemarks (typed, not checked): ").append(r.get("remarks"));
        }
        if (pos != null)
            sb.append("\nPosition: ").append(pos.get("source_label")).append(", ").append(pos.get("latitude"))
                    .append(", ").append(pos.get("longitude"));
        return sb.toString();
    }

    /** The position as a form shows it: the summary, and any accuracy warning. */
    static String positionText(PositionPolicy.Check c) {
        String s = c.summary();
        if (c.verdict == PositionPolicy.Verdict.OK_WITH_WARNING)
            s += "\n" + c.message;
        return s;
    }

    private void message(String title, CharSequence text) {
        PetDialogs.showMessage(pluginContext, title, text);
    }

    private AlertDialog waiting(String title, String text) {
        return PetDialogs.builder(pluginContext, title, PetDialogs.text(pluginContext, text))
                .setCancelable(false).show();
    }
}

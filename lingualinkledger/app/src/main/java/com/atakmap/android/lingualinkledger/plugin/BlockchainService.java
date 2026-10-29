package com.atakmap.android.lingualinkledger.plugin;

import com.atakmap.coremap.log.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Service class for handling blockchain interactions using OkHttp
 */
public class BlockchainService {

    private static final String TAG = "BlockchainService";
    // App v1 sends nothing to any gateway (D45): the address is not set, so
    // every call below refuses at once and makes no request. Track B's A3a
    // makes the address a setting (PL-20), with layer 4 of the D19
    // safeguard; the bench value then is http://127.0.0.1:5000 with
    // "adb reverse tcp:5000 tcp:5000". Nothing in App v1 calls this class.
    private static final String GATEWAY_BASE_URL = "";
    static final String NOT_SET = "No gateway is set up yet: this version sends nothing.";
    private static final String BLOCKCHAIN_GATEWAY_URL = GATEWAY_BASE_URL + "/create-record";
    private static final String VERIFY_RECORD_URL = GATEWAY_BASE_URL + "/verify-record";
    private static final String HEALTH_CHECK_URL = GATEWAY_BASE_URL + "/health";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    // OkHttp client with proper timeouts
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.SECONDS) // 2.5 minutes for blockchain operations
            .build();

    /**
     * Interface for blockchain operation callbacks
     */
    public interface BlockchainCallback {
        void onSuccess(BlockchainResponse response);
        void onError(String errorMessage);
    }

    /**
     * Interface for health check callbacks
     */
    public interface HealthCheckCallback {
        void onSuccess(HealthResponse response);
        void onError(String errorMessage);
    }

    /**
     * Data class to hold blockchain response
     */
    public static class BlockchainResponse {
        public final long gasUsed;
        public final String message;
        public final int recordId;
        public final boolean success;
        public final String transactionHash;

        public BlockchainResponse(long gasUsed, String message, int recordId,
                                  boolean success, String transactionHash) {
            this.gasUsed = gasUsed;
            this.message = message;
            this.recordId = recordId;
            this.success = success;
            this.transactionHash = transactionHash;
        }

        /**
         * Convert to JSON for storage
         */
        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("tx_hash", transactionHash);
            json.put("record_id", recordId);
            json.put("gas_used", gasUsed);
            json.put("message", message);
            json.put("success", success);
            return json;
        }
    }

    /**
     * Data class to hold health check response
     */
    public static class HealthResponse {
        public final String account;
        public final boolean connected;
        public final String contractAddress;
        public final double ethBalance;
        public final String ethBalanceFormatted;
        public final long latestBlock;
        public final String status;
        public final int totalRecords;

        public HealthResponse(String account, boolean connected, String contractAddress,
                              double ethBalance, String ethBalanceFormatted, long latestBlock,
                              String status, int totalRecords) {
            this.account = account;
            this.connected = connected;
            this.contractAddress = contractAddress;
            this.ethBalance = ethBalance;
            this.ethBalanceFormatted = ethBalanceFormatted;
            this.latestBlock = latestBlock;
            this.status = status;
            this.totalRecords = totalRecords;
        }
    }

    /**
     * Submit a file hash to the blockchain
     *
     * @param fileHash The SHA-256 hash of the file (without 0x prefix)
     * @param callback Callback for success/failure
     */
    public static void submitFileHash(String fileHash, BlockchainCallback callback) {
        if (GATEWAY_BASE_URL.isEmpty()) { // D45: no request is made
            callback.onError(NOT_SET);
            return;
        }
        try {
            // Create JSON request body
            JSONObject requestJson = new JSONObject();
            requestJson.put("fileHash", "0x" + fileHash);

            RequestBody body = RequestBody.create(requestJson.toString(), JSON);
            Request request = new Request.Builder()
                    .url(BLOCKCHAIN_GATEWAY_URL)
                    .post(body)
                    .addHeader("User-Agent", "ATAK-LinguaLinkLedger/1.0")
                    .build();

            Log.d(TAG, "Submitting hash to blockchain: " + fileHash);

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Blockchain request failed", e);
                    callback.onError("Network error: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        if (!response.isSuccessful()) {
                            String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                            Log.e(TAG, "Blockchain request unsuccessful: " + response.code() + " - " + errorBody);
                            callback.onError("Server error (" + response.code() + "): " + errorBody);
                            return;
                        }

                        String responseBody = response.body().string();
                        Log.d(TAG, "Blockchain response: " + responseBody);

                        JSONObject jsonResponse = new JSONObject(responseBody);

                        BlockchainResponse blockchainResponse = new BlockchainResponse(
                                jsonResponse.optLong("gas_used", 0),
                                jsonResponse.optString("message", ""),
                                jsonResponse.optInt("record_id", -1),
                                jsonResponse.optBoolean("success", false),
                                jsonResponse.optString("transaction_hash", "")
                        );

                        if (blockchainResponse.success) {
                            callback.onSuccess(blockchainResponse);
                        } else {
                            callback.onError(blockchainResponse.message);
                        }

                    } catch (JSONException e) {
                        Log.e(TAG, "Failed to parse blockchain response", e);
                        callback.onError("Invalid response format: " + e.getMessage());
                    } finally {
                        response.close();
                    }
                }
            });

        } catch (JSONException e) {
            Log.e(TAG, "Failed to create request", e);
            callback.onError("Failed to create request: " + e.getMessage());
        }
    }

    /**
     * Verify/sign a record on the blockchain
     *
     * @param recordId The ID of the record to verify
     * @param role The role of the verifier (trucker or processor)
     * @param remarks Remarks from the verifier
     * @param callback Callback for success/failure
     */
    public static void verifyRecord(int recordId, String role, String remarks, BlockchainCallback callback) {
        if (GATEWAY_BASE_URL.isEmpty()) { // D45: no request is made
            callback.onError(NOT_SET);
            return;
        }
        try {
            // Create JSON request body
            JSONObject requestJson = new JSONObject();
            requestJson.put("recordId", recordId);
            requestJson.put("role", role);
            requestJson.put("remarks", remarks);

            RequestBody body = RequestBody.create(requestJson.toString(), JSON);
            Request request = new Request.Builder()
                    .url(VERIFY_RECORD_URL)
                    .post(body)
                    .addHeader("User-Agent", "ATAK-LinguaLinkLedger/1.0")
                    .build();

            Log.d(TAG, "Verifying record: " + recordId + " as " + role);

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Verify record request failed", e);
                    callback.onError("Network error: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        if (!response.isSuccessful()) {
                            String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                            Log.e(TAG, "Verify record request unsuccessful: " + response.code() + " - " + errorBody);
                            callback.onError("Server error (" + response.code() + "): " + errorBody);
                            return;
                        }

                        String responseBody = response.body().string();
                        Log.d(TAG, "Verify record response: " + responseBody);

                        JSONObject jsonResponse = new JSONObject(responseBody);

                        // For verify-record, there's no record_id returned, so we use -1 as fallback
                        BlockchainResponse blockchainResponse = new BlockchainResponse(
                                jsonResponse.optLong("gas_used", 0),
                                jsonResponse.optString("message", ""),
                                -1, // No record ID returned for verify operations
                                jsonResponse.optBoolean("success", false),
                                jsonResponse.optString("transaction_hash", "")
                        );

                        if (blockchainResponse.success) {
                            callback.onSuccess(blockchainResponse);
                        } else {
                            callback.onError(blockchainResponse.message);
                        }

                    } catch (JSONException e) {
                        Log.e(TAG, "Failed to parse verify record response", e);
                        callback.onError("Invalid response format: " + e.getMessage());
                    } finally {
                        response.close();
                    }
                }
            });

        } catch (JSONException e) {
            Log.e(TAG, "Failed to create verify record request", e);
            callback.onError("Failed to create request: " + e.getMessage());
        }
    }

    /**
     * Check blockchain gateway health
     *
     * @param callback Callback for success/failure
     */
    public static void checkHealth(HealthCheckCallback callback) {
        if (GATEWAY_BASE_URL.isEmpty()) { // D45: no request is made
            callback.onError(NOT_SET);
            return;
        }
        Request request = new Request.Builder()
                .url(HEALTH_CHECK_URL)
                .get()
                .addHeader("User-Agent", "ATAK-LinguaLinkLedger/1.0")
                .build();

        Log.d(TAG, "Checking blockchain gateway health");

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Health check failed", e);
                callback.onError("Network error: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (!response.isSuccessful()) {
                        String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                        Log.e(TAG, "Health check unsuccessful: " + response.code() + " - " + errorBody);
                        callback.onError("Server error (" + response.code() + "): " + errorBody);
                        return;
                    }

                    String responseBody = response.body().string();
                    Log.d(TAG, "Health check response: " + responseBody);

                    JSONObject jsonResponse = new JSONObject(responseBody);

                    HealthResponse healthResponse = new HealthResponse(
                            jsonResponse.optString("account", ""),
                            jsonResponse.optBoolean("connected", false),
                            jsonResponse.optString("contract_address", ""),
                            jsonResponse.optDouble("eth_balance", 0.0),
                            jsonResponse.optString("eth_balance_formatted", ""),
                            jsonResponse.optLong("latest_block", 0),
                            jsonResponse.optString("status", ""),
                            jsonResponse.optInt("total_records", 0)
                    );

                    callback.onSuccess(healthResponse);

                } catch (JSONException e) {
                    Log.e(TAG, "Failed to parse health check response", e);
                    callback.onError("Invalid response format: " + e.getMessage());
                } finally {
                    response.close();
                }
            }
        });
    }

    /**
     * Save blockchain transaction details to file
     *
     * @param hashedPackageDir Directory to save the file in
     * @param fileHash Original file hash (for filename generation)
     * @param response Blockchain response to save
     * @return true if successful, false otherwise
     */
    public static boolean saveTransactionRecord(File hashedPackageDir, String fileHash,
                                                BlockchainResponse response) {
        try {
            // Generate filename using first 6 chars of file hash
            String fileName = fileHash.substring(fileHash.length() - 6) + ".ethtx.json";
            File file = new File(hashedPackageDir, fileName);

            // Write JSON to file
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                writer.write(response.toJson().toString(2)); // Pretty print with indentation
                Log.d(TAG, "Transaction record saved: " + fileName);
                return true;
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to save transaction record", e);
            return false;
        }
    }
}
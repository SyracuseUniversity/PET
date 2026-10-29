from flask import Flask, request, jsonify
from web3 import Web3
import json
import os
from dotenv import load_dotenv
from werkzeug.middleware.proxy_fix import ProxyFix
import requests

# Configuration
SEPOLIA_RPC_URL = os.getenv('SEPOLIA_RPC_URL')
INFURA_GAS_API = f"https://gas.api.infura.io/v3/{SEPOLIA_RPC_URL.split('/')[-1]}/networks/11155111/suggestedGasFees"
CONTRACT_ADDRESS = ""
DEPLOYMENT_BLOCK = 8823000
PRIVATE_KEY = os.getenv('PRIVATE_KEY')

def get_gas_prices():
    """Get current gas prices from Infura API"""
    try:
        response = requests.get(INFURA_GAS_API, timeout=5)
        data = response.json()
        
        # Use "medium" priority for balance of speed and cost
        max_fee = float(data['high']['suggestedMaxFeePerGas']) * 1.3
        priority_fee = float(data['high']['suggestedMaxPriorityFeePerGas']) * 1.3
        
        return {
            'max_fee_per_gas_gwei': max_fee,
            'max_priority_fee_per_gas_gwei': priority_fee,
            'gas_limit_create': 300000,
            'gas_limit_verify': 300000,
            'gas_limit_deactivate': 300000
        }
        
    except Exception as e:
        print(f"Error fetching gas prices: {e}")
        # Fallback to reasonable defaults
        return {
            'max_fee_per_gas_gwei': 15.0,
            'max_priority_fee_per_gas_gwei': 2.0,
            'gas_limit_create': 300000,
            'gas_limit_verify': 300000,
            'gas_limit_deactivate': 300000
        }
    

# Load environment variables
load_dotenv()

app = Flask(__name__)
app.wsgi_app = ProxyFix(app.wsgi_app, x_for=1, x_proto=1, x_host=1)


# Initialize Web3
w3 = Web3(Web3.HTTPProvider(SEPOLIA_RPC_URL))
account = w3.eth.account.from_key(PRIVATE_KEY)

# Load contract ABI
with open('TimberDemo.json', 'r') as f:
    contract_data = json.load(f)
    contract_abi = contract_data['abi']

# Initialize contract
contract = w3.eth.contract(address=CONTRACT_ADDRESS, abi=contract_abi)

@app.route('/health', methods=['GET'])
def health_check():
    try:
        block = w3.eth.block_number
        record_count = contract.functions.recordCount().call()
        
        # Get ETH balance
        balance_wei = w3.eth.get_balance(account.address)
        balance_eth = w3.from_wei(balance_wei, 'ether')
        
        return jsonify({
            "status": "healthy",
            "connected": True,
            "latest_block": block,
            "account": account.address,
            "contract_address": CONTRACT_ADDRESS,
            "total_records": record_count,
            "eth_balance": float(balance_eth),
            "eth_balance_formatted": f"{balance_eth:.6f} ETH"
        })
    except Exception as e:
        return jsonify({"status": "error", "message": str(e)}), 500

@app.route('/check-pending', methods=['GET'])
def check_pending():
    try:
        pending_count = w3.eth.get_transaction_count(account.address, 'pending')
        latest_count = w3.eth.get_transaction_count(account.address, 'latest')
        
        return jsonify({
            "latest_nonce": latest_count,
            "pending_nonce": pending_count,
            "stuck_transactions": pending_count - latest_count,
            "account": account.address
        })
    except Exception as e:
        return jsonify({"error": str(e)})

@app.route('/create-record', methods=['POST'])
def create_record():
    try:
        data = request.json
        file_hash = data['fileHash']
        
        # Convert string hash to bytes32 if needed
        if isinstance(file_hash, str):
            if file_hash.startswith('0x'):
                file_hash = bytes.fromhex(file_hash[2:])
            else:
                file_hash = file_hash.encode('utf-8')[:32].ljust(32, b'\x00')
        
        # Build transaction
        gas_config = get_gas_prices()
        nonce = w3.eth.get_transaction_count(account.address, 'pending')
        transaction = contract.functions.createRecord(file_hash).build_transaction({
            'from': account.address,
            'gas': gas_config['gas_limit_create'],
            'maxFeePerGas': w3.to_wei(gas_config['max_fee_per_gas_gwei'], 'gwei'),
            'maxPriorityFeePerGas': w3.to_wei(gas_config['max_priority_fee_per_gas_gwei'], 'gwei'),
            'nonce': nonce
            # 'gasPrice': w3.to_wei(GWEI, 'gwei'),
            # 'gas': GAS,
            # 'nonce': w3.eth.get_transaction_count(account.address)
        })
        
        # Sign and send transaction
        signed_txn = w3.eth.account.sign_transaction(transaction, PRIVATE_KEY)
        tx_hash = w3.eth.send_raw_transaction(signed_txn.raw_transaction)
        
        # Wait for transaction to be mined and get the record ID
        receipt = w3.eth.wait_for_transaction_receipt(tx_hash)
        
        # Get the current record count (which is the ID of the record we just created)
        # record_id = contract.functions.recordCount().call()
        try:
            rich_logs = contract.events.RecordCreated().process_receipt(receipt)
            if rich_logs:
                record_id = rich_logs[0]['args']['recordId']
                app.logger.info(f"Record created with ID: {record_id} from event")
            else:
                # Fallback to reading contract state (less reliable)
                record_id = contract.functions.recordCount().call()
                app.logger.warning(f"Used fallback method, record ID: {record_id}")
        except Exception as event_error:
            # Fallback in case event parsing fails
            app.logger.error(f"Event parsing failed: {event_error}")
            record_id = contract.functions.recordCount().call()
        
        return jsonify({
            "success": True,
            "transaction_hash": tx_hash.hex(),
            "record_id": record_id,
            "gas_used": receipt.gasUsed,
            "message": "Record created successfully"
        })
        
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500

@app.route('/verify-record', methods=['POST'])
def verify_record():
    try:
        data = request.json
        record_id = int(data['recordId'])
        role = data.get('role', 'inspector')  # Default role
        remarks = data.get('remarks', '')    # Default empty remarks
        
        # Build transaction
        gas_config = get_gas_prices()
        nonce = w3.eth.get_transaction_count(account.address, 'pending')
        transaction = contract.functions.verifyRecord(record_id, role, remarks).build_transaction({
            'from': account.address,
            'gas': gas_config['gas_limit_verify'],
            'maxFeePerGas': w3.to_wei(gas_config['max_fee_per_gas_gwei'], 'gwei'),
            'maxPriorityFeePerGas': w3.to_wei(gas_config['max_priority_fee_per_gas_gwei'], 'gwei'),
            'nonce': nonce
            # 'gasPrice': w3.to_wei(GWEI, 'gwei'),
            # 'gas': GAS,
            # 'nonce': w3.eth.get_transaction_count(account.address)
        })
        
        # Sign and send transaction
        signed_txn = w3.eth.account.sign_transaction(transaction, PRIVATE_KEY)
        tx_hash = w3.eth.send_raw_transaction(signed_txn.raw_transaction)
        
        # Wait for confirmation
        receipt = w3.eth.wait_for_transaction_receipt(tx_hash)
        
        return jsonify({
            "success": True,
            "transaction_hash": tx_hash.hex(),
            "gas_used": receipt.gasUsed,
            "message": f"Record verified by {role}"
        })
        
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500

@app.route('/get-record', methods=['GET'])
def get_record():
    try:
        record_id = int(request.args.get('recordId'))
        record = contract.functions.getRecord(record_id).call()
        
        return jsonify({
            "success": True,
            "record": {
                "id": record_id,
                "fileHash": record[0].hex(),
                "creator": record[1],
                "createdAt": record[2],
                "isActive": record[3]
            }
        })
        
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500

@app.route('/get-verifications', methods=['GET'])
def get_verifications():
    try:
        record_id = int(request.args.get('recordId'))
        verifications = contract.functions.getAllVerifications(record_id).call()
        
        verification_list = []
        for i, verification in enumerate(verifications):
            verification_list.append({
                "index": i,
                "verifier": verification[0],
                "role": verification[1],
                "remarks": verification[2],
                "timestamp": verification[3],
                "isActive": verification[4]
            })
        
        return jsonify({
            "success": True,
            "record_id": record_id,
            "verification_count": len(verification_list),
            "verifications": verification_list
        })
        
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500

@app.route('/get-chain-of-custody', methods=['GET'])
def get_chain_of_custody():
    """Get complete chain of custody for a record"""
    try:
        record_id = int(request.args.get('recordId'))
        
        # Get record details
        record = contract.functions.getRecord(record_id).call()
        
        # Get all verifications
        verifications = contract.functions.getAllVerifications(record_id).call()
        
        # Get transaction hashes from events
        try:
            # Get creation transaction hash
            creation_filter = contract.events.RecordCreated.create_filter(
                from_block=DEPLOYMENT_BLOCK,
                to_block='latest',
                argument_filters={'recordId': record_id}
            )
            creation_events = creation_filter.get_all_entries()
            creation_tx = f"0x{creation_events[0]['transactionHash'].hex()}" if creation_events else None
            
            # Get verification transaction hashes
            verification_filter = contract.events.RecordVerified.create_filter(
                from_block=DEPLOYMENT_BLOCK,
                to_block='latest', 
                argument_filters={'recordId': record_id}
            )
            verification_events = verification_filter.get_all_entries()
            verification_txs = [f"0x{event['transactionHash'].hex()}" for event in verification_events]
            
        except Exception as e:
            print(f"Error fetching transaction hashes: {e}")
            creation_tx = None
            verification_txs = []
        
        verification_list = []
        for i, verification in enumerate(verifications):
            verification_list.append({
                "step": i + 1,
                "verifier": verification[0],
                "role": verification[1],
                "remarks": verification[2],
                "timestamp": verification[3],
                "date": verification[3],
                "transaction_hash": verification_txs[i] if i < len(verification_txs) else None
            })
        
        return jsonify({
            "success": True,
            "record_id": record_id,
            "record_details": {
                "fileHash": record[0].hex(),
                "creator": record[1],
                "createdAt": record[2],
                "isActive": record[3],
                "creation_transaction": creation_tx
            },
            "chain_of_custody": verification_list,
            "total_verifications": len(verification_list)
        })
        
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500

@app.route('/deactivate-record', methods=['POST'])
def deactivate_record():
    try:
        data = request.json
        record_id = int(data['recordId'])
        
        # Build transaction
        gas_config = get_gas_prices()
        nonce = w3.eth.get_transaction_count(account.address, 'pending')
        transaction = contract.functions.deactivateRecord(record_id).build_transaction({
            'from': account.address,
            'gas': gas_config['gas_limit_create'],
            'maxFeePerGas': w3.to_wei(gas_config['max_fee_per_gas_gwei'], 'gwei'),
            'maxPriorityFeePerGas': w3.to_wei(gas_config['max_priority_fee_per_gas_gwei'], 'gwei'),
            'nonce': nonce
            # 'gasPrice': w3.to_wei(GWEI, 'gwei'),
            # 'gas': GAS,
            # 'nonce': w3.eth.get_transaction_count(account.address)
        })
        
        # Sign and send transaction
        signed_txn = w3.eth.account.sign_transaction(transaction, PRIVATE_KEY)
        tx_hash = w3.eth.send_raw_transaction(signed_txn.raw_transaction)
        
        # Wait for confirmation
        receipt = w3.eth.wait_for_transaction_receipt(tx_hash)
        
        return jsonify({
            "success": True,
            "transaction_hash": tx_hash.hex(),
            "message": "Record deactivated successfully",
            "gas_used": receipt.gasUsed
        })
        
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


@app.route('/dashboard')
def dashboard():
    return '''
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Timber Chain of Custody Dashboard</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css" rel="stylesheet">
    <link href="https://cdn.jsdelivr.net/npm/bootstrap-icons@1.7.2/font/bootstrap-icons.css" rel="stylesheet">
    <link href="https://cdn.datatables.net/1.13.6/css/dataTables.bootstrap5.min.css" rel="stylesheet">
    <style>
        .chain-step {
            padding: 12px 20px;
            margin: 0 8px;
            border-radius: 25px;
            font-weight: 600;
            text-align: center;
            min-width: 120px;
            transition: all 0.3s ease;
        }
        .chain-step.active {
            background: linear-gradient(135deg, #28a745, #20c997);
            color: white;
            box-shadow: 0 4px 12px rgba(40, 167, 69, 0.3);
        }
        .chain-step.inactive {
            background: #e9ecef;
            color: #6c757d;
        }
        .chain-arrow {
            font-size: 24px;
            color: #dee2e6;
            margin: 0 10px;
        }
        .chain-container {
            display: flex;
            align-items: center;
            justify-content: center;
            margin: 20px 0;
            flex-wrap: wrap;
        }
        .record-card {
            border: 1px solid #e9ecef;
            border-radius: 12px;
            margin-bottom: 20px;
            transition: all 0.3s ease;
        }
        .record-card:hover {
            box-shadow: 0 8px 25px rgba(0,0,0,0.1);
            transform: translateY(-2px);
        }
        .hash-format {
            font-family: 'Courier New', monospace;
            background: #f8f9fa;
            padding: 2px 6px;
            border-radius: 4px;
        }
        .loading {
            text-align: center;
            padding: 40px;
        }
        .status-badge {
            display: inline-flex;
            align-items: center;
            gap: 5px;
        }
    </style>
</head>
<body>
    <div class="container-fluid py-4">
        <div class="row">
            <div class="col-12">
                <div class="d-flex justify-content-between align-items-center mb-4">
                    <h1 class="mb-0"><i class="bi bi-tree"></i> Timber Chain of Custody Dashboard</h1>
                    <button class="btn btn-primary" onclick="refreshData()">
                        <i class="bi bi-arrow-clockwise"></i> Refresh
                    </button>
                </div>
                
                <div id="systemStatus" class="alert alert-info">
                    <i class="bi bi-info-circle"></i> Loading system status...
                </div>

                <div id="recordsList" class="loading">
                    <div class="spinner-border text-primary" role="status">
                        <span class="visually-hidden">Loading...</span>
                    </div>
                    <p class="mt-3">Loading records...</p>
                </div>
            </div>
        </div>
    </div>

    <!-- Record Detail Modal -->
    <div class="modal fade" id="recordModal" tabindex="-1">
        <div class="modal-dialog modal-xl modal-dialog-scrollable">
            <div class="modal-content">
                <div class="modal-header">
                    <h5 class="modal-title">Record Details</h5>
                    <button type="button" class="btn-close" data-bs-dismiss="modal"></button>
                </div>
                <div class="modal-body" id="recordModalBody">
                    <!-- Content will be loaded dynamically -->
                </div>
            </div>
        </div>
    </div>

    <script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/js/bootstrap.bundle.min.js"></script>
    <script src="https://code.jquery.com/jquery-3.7.0.min.js"></script>
    <script src="https://cdn.datatables.net/1.13.6/js/jquery.dataTables.min.js"></script>
    <script src="https://cdn.datatables.net/1.13.6/js/dataTables.bootstrap5.min.js"></script>
    <script>
        let recordsData = [];
        let isFirstLoad = true;
        
        function formatAddress(address) {
            return address.slice(0, 6) + '...' + address.slice(-4);
        }
        
        function formatTimestamp(timestamp) {
            return new Date(timestamp * 1000).toLocaleString();
        }
        
        function createChainStatus(verificationCount) {
            const steps = ['Logger', 'Trucker', 'Processor'];
            let html = '<div class="chain-container">';
            
            for (let i = 0; i < steps.length; i++) {
                const isActive = i <= verificationCount;
                html += `<div class="chain-step ${isActive ? 'active' : 'inactive'}">${steps[i]}</div>`;
                if (i < steps.length - 1) {
                    html += '<i class="bi bi-arrow-right chain-arrow"></i>';
                }
            }
            
            html += '</div>';
            return html;
        }
        
        async function loadSystemStatus() {
            try {
                const response = await fetch('/health');
                const data = await response.json();
                
                document.getElementById('systemStatus').innerHTML = `
                    <div class="row">
                        <div class="col-md-2">
                            <strong>Status:</strong> <span class="badge bg-success">Connected</span>
                        </div>
                        <div class="col-md-2">
                            <strong>Network:</strong> Ethereum Sepolia Testnet
                        </div>
                        <div class="col-md-2">
                            <strong>Total Records:</strong> ${data.total_records}
                        </div>
                        <div class="col-md-2">
                            <strong>Latest Block:</strong> ${data.latest_block}
                        </div>
                        <div class="col-md-2">
                            <strong>Balance:</strong> ${data.eth_balance_formatted || 'N/A'}
                        </div>
                        <div class="col-md-2">
                            <strong>Account:</strong> <span class="hash-format">${formatAddress(data.account)}</span>
                        </div>
                    </div>
                `;
                
                return data.total_records;
            } catch (error) {
                document.getElementById('systemStatus').innerHTML = `
                    <div class="alert alert-danger">
                        <i class="bi bi-exclamation-triangle"></i> Error connecting to blockchain: ${error.message}
                    </div>
                `;
                return 0;
            }
        }
        
        async function loadAllRecords(totalRecords) {
            const previousRecordsData = [...recordsData];
            recordsData = [];
            
            if (isFirstLoad) {
                // First load: get all records
                console.log('First load: fetching all records');
                for (let i = 1; i <= totalRecords; i++) {
                    try {
                        const custodyResponse = await fetch(`/get-chain-of-custody?recordId=${i}`);
                        const custodyData = await custodyResponse.json();
                        
                        if (custodyData.success) {
                            recordsData.push({
                                id: i,
                                record: custodyData.record_details,
                                custody: custodyData
                            });
                        }
                    } catch (error) {
                        console.error(`Error loading record ${i}:`, error);
                    }
                }
                isFirstLoad = false;
            } else {
                // Subsequent loads: only refresh active records + keep inactive ones unchanged
                console.log('Refresh: fetching only previously active records and new records');
                
                // Keep inactive records from previous load (no need to re-fetch)
                const inactiveRecords = previousRecordsData.filter(item => !item.record.isActive);
                recordsData = [...inactiveRecords];
                
                // Get list of previously active record IDs
                const previouslyActiveIds = previousRecordsData
                    .filter(item => item.record.isActive)
                    .map(item => item.id);
                
                // Also check for any new records beyond our previous max
                const previousMaxId = previousRecordsData.length > 0 ? 
                    Math.max(...previousRecordsData.map(item => item.id)) : 0;
                
                const recordsToCheck = new Set([
                    ...previouslyActiveIds,
                    // Add any new record IDs
                    ...Array.from({length: Math.max(0, totalRecords - previousMaxId)}, 
                        (_, i) => previousMaxId + i + 1)
                ]);
                
                // Fetch only records that were active or are new
                for (const recordId of recordsToCheck) {
                    try {
                        const custodyResponse = await fetch(`/get-chain-of-custody?recordId=${recordId}`);
                        const custodyData = await custodyResponse.json();
                        
                        if (custodyData.success) {
                            // Remove any existing entry for this record and add updated one
                            recordsData = recordsData.filter(item => item.id !== recordId);
                            recordsData.push({
                                id: recordId,
                                record: custodyData.record_details,
                                custody: custodyData
                            });
                        }
                    } catch (error) {
                        console.error(`Error loading record ${recordId}:`, error);
                    }
                }
                
                // Sort by record ID
                recordsData.sort((a, b) => a.id - b.id);
            }
            
            renderRecordsTable();
        }
        
        function renderRecordsTable() {
            const container = document.getElementById('recordsList');
            
            if (recordsData.length === 0) {
                container.innerHTML = '<div class="alert alert-warning">No records found.</div>';
                return;
            }
            
            let html = `
                <div class="table-responsive">
                    <table id="recordsTable" class="table table-hover">
                        <thead class="table-dark">
                            <tr>
                                <th>Record ID</th>
                                <th>File Hash</th>
                                <th>Creator</th>
                                <th>Created At</th>
                                <th>Verifications</th>
                                <th>Status</th>
                                <th>Actions</th>
                            </tr>
                        </thead>
                        <tbody>
            `;
            
            recordsData.forEach(item => {
                const record = item.record;
                const custody = item.custody;
                
                html += `
                    <tr>
                        <td>${item.id}</td>
                        <td><span class="hash-format">${formatAddress(record.fileHash)}</span></td>
                        <td><span class="hash-format">${formatAddress(record.creator)}</span></td>
                        <td data-order="${record.createdAt}">${formatTimestamp(record.createdAt)}</td>
                        <td data-order="${custody.total_verifications}">${custody.total_verifications}</td>
                        <td data-order="${record.isActive ? 1 : 0}">
                            ${record.isActive ? 
                                '<span class="badge bg-success">Active</span>' : 
                                '<span class="badge bg-danger">Inactive</span>'
                            }
                        </td>
                        <td>
                            <button class="btn btn-sm btn-outline-primary" onclick="showRecordDetail(${item.id})">
                                <i class="bi bi-eye"></i> View Details
                            </button>
                        </td>
                    </tr>
                `;
            });
            
            html += '</tbody></table></div>';
            container.innerHTML = html;
            
            // Initialize DataTable for sorting and filtering
            $('#recordsTable').DataTable({
                responsive: true,
                pageLength: 25,
                order: [[0, 'desc']],
                columnDefs: [
                    { orderable: false, targets: [6] } // Actions column not sortable
                ]
            });
        }
        
        function showRecordDetail(recordId) {
            const recordData = recordsData.find(r => r.id === recordId);
            if (!recordData) return;
            
            const record = recordData.record;
            const custody = recordData.custody;
            
            let modalContent = `
                <div class="row mb-4">
                    <div class="col-12">
                        <h6>Chain of Custody Status</h6>
                        ${createChainStatus(custody.total_verifications)}
                    </div>
                </div>
                
                <div class="row mb-4">
                    <div class="col-12">
                        <h6>Record Information</h6>
                        <div class="table-responsive">
                            <table class="table table-sm">
                                <tr><td style="width: 25%;"><strong>Record ID:</strong></td><td>${recordId}</td></tr>
                                <tr><td><strong>File Hash:</strong></td><td><span class="hash-format" style="word-break: break-all; font-size: 0.9em;">${record.fileHash}</span></td></tr>
                                <tr><td><strong>Creator:</strong></td><td><span class="hash-format">${record.creator}</span></td></tr>
                                <tr><td><strong>Created:</strong></td><td>${formatTimestamp(record.createdAt)}</td></tr>
                                <tr><td><strong>Status:</strong></td><td>${record.isActive ? 
                                    '<span class="badge bg-success">Active</span>' : 
                                    '<span class="badge bg-danger">Inactive</span>'
                                }</td></tr>
                                <tr><td><strong>Transaction:</strong></td><td>${record.creation_transaction ? 
                                    `<a href="https://sepolia.etherscan.io/tx/${record.creation_transaction}" target="_blank" class="btn btn-sm btn-outline-primary">View</a>` : 
                                    '<span class="text-muted">N/A</span>'
                                }</td></tr>
                            </table>
                        </div>
                    </div>
                </div>
                
                <div class="row mb-4">
                    <div class="col-12">
                        <h6>Verification Summary</h6>
                        <div class="table-responsive">
                            <table class="table table-sm">
                                <tr><td style="width: 25%;"><strong>Total Verifications:</strong></td><td>${custody.total_verifications}</td></tr>
                                <tr><td><strong>Last Updated:</strong></td><td>${
                                    custody.chain_of_custody.length > 0 ? 
                                    formatTimestamp(custody.chain_of_custody[custody.chain_of_custody.length - 1].timestamp) : 
                                    'Never'
                                }</td></tr>
                            </table>
                        </div>
                    </div>
                </div>
            `;
            
            if (custody.chain_of_custody.length > 0) {
                modalContent += `
                    <h6>Verification History</h6>
                    <div class="table-responsive">
                        <table class="table table-striped table-sm">
                            <thead>
                                <tr>
                                    <th>Step</th>
                                    <th>Role</th>
                                    <th>Verifier</th>
                                    <th>Timestamp</th>
                                    <th>Remarks</th>
                                    <th>Transaction</th>
                                </tr>
                            </thead>
                            <tbody>
                `;
                
                custody.chain_of_custody.forEach((verification, index) => {
                    modalContent += `
                        <tr>
                            <td><span class="badge bg-secondary">${verification.step}</span></td>
                            <td><span class="badge bg-info">${verification.role}</span></td>
                            <td><span class="hash-format">${formatAddress(verification.verifier)}</span></td>
                            <td>${formatTimestamp(verification.timestamp)}</td>
                            <td>${verification.remarks || '<em>No remarks</em>'}</td>
                            <td>${verification.transaction_hash ? 
                                `<a href="https://sepolia.etherscan.io/tx/${verification.transaction_hash}" target="_blank" class="btn btn-sm btn-outline-secondary">View</a>` : 
                                '<span class="text-muted">N/A</span>'
                            }</td>
                        </tr>
                    `;
                });
                
                modalContent += '</tbody></table></div>';
            } else {
                modalContent += '<div class="alert alert-info">No verifications yet.</div>';
            }
            
            document.getElementById('recordModalBody').innerHTML = modalContent;
            new bootstrap.Modal(document.getElementById('recordModal')).show();
        }
        
        async function refreshData() {
            // Destroy existing DataTable if it exists
            if ($.fn.DataTable.isDataTable('#recordsTable')) {
                $('#recordsTable').DataTable().destroy();
            }
            
            const loadingMessage = isFirstLoad ? 
                'Loading all records...' : 
                'Refreshing active records...';
            
            document.getElementById('recordsList').innerHTML = `
                <div class="loading">
                    <div class="spinner-border text-primary" role="status"></div>
                    <p class="mt-3">${loadingMessage}</p>
                </div>
            `;
            
            const totalRecords = await loadSystemStatus();
            if (totalRecords > 0) {
                await loadAllRecords(totalRecords);
            } else {
                document.getElementById('recordsList').innerHTML = '<div class="alert alert-warning">No records found.</div>';
            }
        }
        
        // Load data on page load
        document.addEventListener('DOMContentLoaded', refreshData);
    </script>
</body>
</html>
    '''

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, debug=True)
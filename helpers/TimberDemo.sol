// SPDX-License-Identifier: MIT
pragma solidity ^0.8.28;

contract TimberDemo {
    struct Record {
        bytes32 fileHash;
        address creator;
        uint256 createdAt;
        bool isActive;
    }

    struct Verification {
        address verifier;
        string role;
        string remarks;
        uint256 timestamp;
        bool isActive;
    }

    uint public recordCount;
    mapping(uint => Record) public records;

    // recordId => verificationIndex => Verification
    mapping(uint => mapping(uint => Verification)) public verifications;
    // recordId => number of verifications
    mapping(uint => uint) public verificationCounts;

    // events for chain of custody tracking
    event RecordCreated(
        uint indexed recordId,
        bytes32 fileHash,
        address indexed creator,
        uint256 timestamp
    );
    event RecordVerified(
        uint indexed recordId,
        address indexed verifier,
        string role,
        string remarks,
        uint256 timestamp
    );
    event RecordDeactivated(
        uint indexed recordId,
        address indexed deactivator,
        uint256 timestamp
    );

    // sample roles
    mapping(string => bool) public validRoles;

    constructor() {
        validRoles["logger"] = true;
        validRoles["trucker"] = true;
        validRoles["processor"] = true;
    }

    modifier onlyValidRole(string memory _role) {
        require(validRoles[_role], "Invalid role");
        _;
    }

    modifier recordExists(uint _recordId) {
        require(
            _recordId > 0 && _recordId <= recordCount,
            "Record does not exist"
        );
        _;
    }

    modifier recordActive(uint _recordId) {
        require(records[_recordId].isActive, "Record is not active");
        _;
    }

    function createRecord(bytes32 _fileHash) external returns (uint) {
        recordCount++;
        records[recordCount] = Record({
            fileHash: _fileHash,
            creator: msg.sender,
            createdAt: block.timestamp,
            isActive: true
        });

        emit RecordCreated(recordCount, _fileHash, msg.sender, block.timestamp);
        return recordCount;
    }

    function verifyRecord(
        uint _recordId,
        string memory _role,
        string memory _remarks
    )
        external
        recordExists(_recordId)
        recordActive(_recordId)
        onlyValidRole(_role)
    {
        uint verificationIndex = verificationCounts[_recordId];

        verifications[_recordId][verificationIndex] = Verification({
            verifier: msg.sender,
            role: _role,
            remarks: _remarks,
            timestamp: block.timestamp,
            isActive: true
        });

        verificationCounts[_recordId]++;

        emit RecordVerified(
            _recordId,
            msg.sender,
            _role,
            _remarks,
            block.timestamp
        );
    }

    function deactivateRecord(uint _recordId) external recordExists(_recordId) {
        records[_recordId].isActive = false;
        emit RecordDeactivated(_recordId, msg.sender, block.timestamp);
    }

    // view funcs
    function getRecord(
        uint _recordId
    ) external view recordExists(_recordId) returns (Record memory) {
        return records[_recordId];
    }

    function getVerification(
        uint _recordId,
        uint _verificationIndex
    ) external view recordExists(_recordId) returns (Verification memory) {
        require(
            _verificationIndex < verificationCounts[_recordId],
            "Verification does not exist"
        );
        return verifications[_recordId][_verificationIndex];
    }

    function getVerificationCount(
        uint _recordId
    ) external view recordExists(_recordId) returns (uint) {
        return verificationCounts[_recordId];
    }

    function getAllVerifications(
        uint _recordId
    ) external view recordExists(_recordId) returns (Verification[] memory) {
        uint count = verificationCounts[_recordId];
        Verification[] memory allVerifications = new Verification[](count);

        for (uint i = 0; i < count; i++) {
            allVerifications[i] = verifications[_recordId][i];
        }

        return allVerifications;
    }

    function addValidRole(string memory _role) external {
        // add access control here
        validRoles[_role] = true;
    }
}

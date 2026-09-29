# PET — Provable Evidence Tracing

PET is an ATAK/CivTAK plugin and blockchain backend for recording forestry supply-chain events as tamper-evident evidence. Field users package data on an Android device running ATAK, and PET fingerprints each package with a SHA-256 hash. The goal is to anchor those hashes on a blockchain, so anyone can later check that a record hasn't been altered and trace its chain of custody (for example, logger → trucker → processor).

PET is developed by the [Dynamic Sustainability Lab (DSL)](#authors) at Syracuse University as part of the USDA AMP Blockchain project.

> **Status: research prototype.** The plugin and the blockchain backend both run, but **they are not yet connected**, so hashes created by the plugin are not written on-chain automatically. The backend targets the Ethereum **Sepolia testnet** only. See [Known limitations](#known-limitations).

---

## Background

Provable Evidence Tracing, in its current form, provides a tamper-proof ledger of supply chain events throughout the forestry industry. Building on the GIS and time stamp from ATAK, these supply chain events are stored on the blockchain, so auditors can prove that no data has been tampered with. It can track a timber batch from the logger, through the transfer of custody to the trucker, and through another transfer of custody to the processor. This is a work in progress, and as progress resumes, Provable Evidence Tracing will be shifting its focus to tracking a seed throughout its time in a nursery.

On the `main` branch, the plugin and the blockchain backend are not yet connected. See [Known limitations](#known-limitations).

## How it works

```mermaid
flowchart LR
    subgraph Device["Android device (ATAK-CIV)"]
        A["PET plugin"] -->|"create data package with metadata"| B[("Data package .zip")]
        B -->|"SHA-256 hash"| C["Hash saved on device"]
        B -->|"send"| D["ATAK Send dialog / TAK Server"]
    end
    subgraph Backend["Blockchain backend"]
        E["Flask gateway"] -->|"signs transaction"| F["TimberDemo contract (Sepolia testnet)"]
    end
    C -.->|"planned: POST /create-record"| E
```

| Step | Component | Status |
|---|---|---|
| Enter package metadata (name, description, author, lat/lon pre-filled from map center) | Plugin | Working |
| Build an ATAK data package containing that metadata | Plugin | Working |
| Compute a SHA-256 hash of a selected data package and store it on the device | Plugin | Working |
| Send the package through ATAK's standard Send dialog | Plugin | Working |
| Record a hash on-chain and return a record ID | Gateway + contract | Working (called directly, not from the plugin) |
| Add role-based verifications (`logger`, `trucker`, `processor`) and read chain-of-custody history | Gateway + contract | Working (called directly) |
| Plugin sends its hash to the gateway automatically | Plugin → Gateway | **Planned** |

## Repository layout

```
PET/
├── lingualinkledger/   ATAK-CIV plugin (Android / Java / Gradle)
│   └── README.md       Build and install instructions
├── witec-demo/         Smart contract, Flask gateway, and deployment scripts
│   └── README.md       Deploy and run instructions
├── SECURITY.md         Security model and known issues
├── CONTRIBUTING.md     How to contribute
└── README.md           This file
```

## Getting started

Each component builds and runs independently. Start with the one you need.

**Plugin** ([`lingualinkledger/README.md`](lingualinkledger/README.md)): requires the [ATAK-CIV SDK](https://github.com/TAK-Product-Center/atak-civ/releases), Android Studio, JDK 17, and an Android device with ATAK-CIV installed. Targets ATAK 5.4.0.

> **Where to put the repo:** clone PET as the `plugins` folder inside the extracted SDK (`git clone https://github.com/SyracuseUniversity/PET.git atak-civ-sdk/plugins`). The plugin build looks for `atak-gradle-takdev.jar` two folders above `lingualinkledger/`, and this puts the plugin at `atak-civ-sdk/plugins/lingualinkledger`, the same path as the original setup. Details are in the plugin README.

**Blockchain backend** ([`witec-demo/README.md`](witec-demo/README.md)): requires Node.js and npm (Hardhat), Python 3 (Flask, web3.py), a Sepolia RPC endpoint (the gateway's gas-price lookup expects an Infura URL), and a funded Sepolia wallet. The reference deployment runs on an Ubuntu 22.04 Azure VM behind nginx.

## Tech stack

| Component | Technologies |
|---|---|
| Plugin | Java, Android (min SDK 21, target SDK 33), ATAK-CIV plugin API, Gradle 8.9 |
| Smart contract | Solidity 0.8.28, Hardhat 2.x, Ethereum Sepolia |
| Gateway | Python, Flask, web3.py, nginx, systemd |

## Component names

This repository combines two earlier repositories. Each one keeps its original name as its folder, and the code still uses those names:

| Folder | Component | Where the name appears in the code |
|---|---|---|
| `lingualinkledger/` | PET plugin (app name **Lingua Link Ledger**) | Java package `com.atakmap.android.lingualinkledger`, app name, on-device folder `atak/tools/lingualinkledger/` |
| `witec-demo/` | PET blockchain backend | VM and service names, domain `witec.io`. The contract is named `TimberDemo` |

## Known limitations

PET is a testnet prototype. The most important limitations:

- **No authentication on the gateway.** Anyone who can reach it can create, verify, or deactivate records, and every call spends the gateway wallet's funds.
- **A single wallet signs every transaction.** On-chain, the creator and verifier addresses are the gateway's, not the individual field user's.
- **No access control in the contract.** Any caller can deactivate records or add new roles.
- **The plugin is not yet connected to the blockchain** (see [Status](#how-it-works)).

See [`SECURITY.md`](SECURITY.md) for details and reporting instructions.

## Contributing

Contributions are welcome. Please read [`CONTRIBUTING.md`](CONTRIBUTING.md) first. It explains how to propose changes, how to test them by hand, and the project's current license status.

## Authors

<!-- Keep one or both of the sections below. -->

### Organization

Developed by the **Dynamic Sustainability Lab (DSL), Syracuse University**.

### Contributors

<!-- TODO: Add missing contact emails or GitHub accounts. -->
| Name | Role | Contact |
|---|---|---|
| Dominick Miceli | Product Lead & Partner Liaison | dcmiceli@syr.edu |
| Darrel Ramasray | Blockchain Developer (App Architecture, Backend) | ddramasr@syr.edu |
| William Cook | UI/UX Developer | kcook22@syr.edu |
| Melanie Thomas | Technical Documentation and GitHub | mthoma72@syr.edu |
| Lee McKnight | Faculty Supervisor | lmcknigh@syr.edu |

**Former contributors**

| Name | Role | Contact |
|---|---|---|
| Rohan Yadav | Backend Developer | https://github.com/rohany395 |
| Arsen Khanin | Blockchain Developer | https://github.com/eternal-dissident |

## License

<!-- TODO: License pending confirmation with DSL / Syracuse University. -->
_License to be determined._ Until a license is added, all rights are reserved by the authors. See [`LICENSE`](LICENSE).

## Acknowledgments

- The plugin is built on the ATAK-CIV plugin template from the [TAK Product Center](https://github.com/TAK-Product-Center/atak-civ).
- This work by the Dynamic Sustainability Lab at Syracuse University is supported by the U.S. Department of Agriculture (USDA) through the Advancing Markets for Forestry (AMP) program. The findings, conclusions, and opinions expressed are those of the authors and do not necessarily reflect the views of the USDA.
  <!-- TODO: Replace with the standard acknowledgment sentence Dr. McKnight is drafting. -->

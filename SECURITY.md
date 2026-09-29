# Security Policy

PET is a **research prototype** that runs on the **Ethereum Sepolia testnet**. It has not had a security review and is **not suitable for production use** or for recording real supply-chain evidence. This document explains the security model, lists the known issues, and describes how to report new ones.

---

## Reporting a vulnerability

**Please do not report security issues in public GitHub issues, discussions, or pull requests.**

**Report through GitHub vulnerability reporting**.   
Go to the repository's **Security** tab and click **Report a vulnerability**. Only the maintainers can see the report.

Please include:

- which component is affected (`lingualinkledger/`, `witec-demo/` contract, or `witec-demo/` gateway)
- steps to reproduce, or a proof of concept
- the impact you expect
- whether the issue affects a running deployment

---

## Security model

PET has three parts, and each one trusts different things:

| Component | What it trusts | What it protects |
|---|---|---|
| **Plugin** (Android / ATAK) | The device and anyone with access to its storage | Computes a SHA-256 hash of a data package and stores it on the device |
| **Gateway** (Flask) | **Every caller**. There is no authentication | Holds the only private key and signs every transaction |
| **Contract** (`TimberDemo`) | **Every caller**. There is no access control | Stores hashes and verifications permanently on a public chain |

This model shapes what PET can and cannot prove today:

- **It can show** that the gateway recorded a given hash at a given block time, and that nobody has changed the stored record since.
- **It cannot show** who in the supply chain created or verified a record. On-chain, every action comes from the gateway's single wallet, and the verifier's role is a text string the caller chooses.
- **It does not yet anchor plugin hashes on-chain.** The plugin keeps its hash on the device only, so until integration is built, tamper evidence depends on that device.

---

## Known issues

### Gateway (`witec-demo/gateway/server.py`)

| Issue | Impact |
|---|---|
| No authentication or rate limiting on any endpoint | Anyone who can reach the server can create, verify, or deactivate records, and every write spends the gateway wallet's funds |
| One server wallet signs every transaction | On-chain `creator` and `verifier` are always the gateway's address, never the field user |
| Private key stored in plain text in `.env` on the server | Anyone with access to the server or its backups can take the wallet |
| `app.run(debug=True)` | Flask debug mode exposes an interactive debugger if an unhandled error reaches it. In the reference setup the app runs under systemd behind nginx, and port 5000 isn't opened in the Azure firewall rules |
| Raw exception text returned to clients | Error responses can reveal internal details (RPC URLs, node errors, file paths) |
| Hashes without a `0x` prefix are truncated to 32 characters | Only half of a SHA-256 hex string is stored, and the call still reports success |
| Record ID fallback in `/create-record` | If the event can't be read, the gateway returns the contract's latest record ID, which can belong to someone else's record |

### Smart contract (`witec-demo/helpers/TimberDemo.sol`)

| Issue | Impact |
|---|---|
| `deactivateRecord()` has no access control | Any address can deactivate any record |
| `addValidRole()` has no access control (`// add access control here`) | Any address can create new roles |
| `verifyRecord()` trusts the role string | Any caller can claim to be a `logger`, `trucker`, or `processor` |

### Plugin (`lingualinkledger/`)

| Issue | Impact |
|---|---|
| `.gitignore` does not exclude `*.keystore` | Following the setup steps, signing keys could be committed to the public repo |
| Hardcoded keystore passwords in `app/build.gradle` | They look like public TAK template defaults. Anything signed with them should not be trusted |
| Hashes and metadata are stored only on the device | Anyone with access to device storage can change a package before it is hashed, or change the stored hash file (it is set read-only, but that is not tamper-proof) |
| `MANAGE_EXTERNAL_STORAGE` permission | Asks for access to all shared storage on the device, though the plugin only uses ATAK's folders. It may not be needed at all, since ATAK runs plugin code under its own permissions (unverified) |

### General

| Issue | Impact |
|---|---|
| Unpinned Python dependencies | Installs aren't reproducible, and a dependency update can change behavior |

---

## Data on the blockchain is public and permanent

Everything the contract stores (file hashes, verifier roles, **remarks**, and timestamps) is publicly readable and cannot be deleted. "Deactivating" a record only sets a flag, and the data stays on-chain.
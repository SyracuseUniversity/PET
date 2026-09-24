# Contributing to PET

Thanks for your interest in PET (Provable Evidence Tracing). Bug reports, questions, documentation fixes, and code are all welcome.

PET is a **research prototype** maintained by the Dynamic Sustainability Lab (DSL) at Syracuse University. Please read the short section below before you start.

---

## Before you start

**1. Open an issue before any large change.**
For anything bigger than a typo or small fix, open an issue describing what you want to change and why, and wait for a maintainer to respond before writing code. The project is changing quickly, and we don't want you to spend weeks on something we can't merge.

**2. Reviews are best effort.**
PET is maintained by a small university research team, not a full-time staff. We will review issues and pull requests as soon as we can, but we can't promise response times.

**3. There are no automated tests or CI yet.**
You'll need to test your changes by hand. [Testing your changes](#testing-your-changes) below lists what to check for each component, and your pull request should say what you tested.

**4. There is no license yet.**
The project doesn't have an open-source license yet. One will be added soon. Until then, we can discuss issues and review pull requests, but **we won't merge contributions from outside DSL until the license is in place.** Once it is, contributions are accepted under that license (see `LICENSE`).
<!-- MAINTAINERS: When LICENSE is added, delete the sentence in bold above. -->

---

## Ways to contribute

- **Report a bug.** Open an issue with steps to reproduce, what you expected, and what happened. Say which component is affected: `lingualinkledger/`, the `witec-demo/` contract, or the `witec-demo/` gateway.
- **Suggest a feature.** Open an issue that describes the problem you're trying to solve.
- **Improve the docs.** Small documentation fixes can go straight to a pull request.
- **Contribute code.** Open an issue first (see above), then a pull request.

**Security issues:** please **don't** open a public issue. Follow [`SECURITY.md`](SECURITY.md) instead.

---

## Development setup

Each component has its own setup guide:

- **Plugin:** [`lingualinkledger/README.md`](lingualinkledger/README.md) (ATAK-CIV 5.4.0 SDK, Android Studio, JDK 17)
- **Blockchain backend:** [`witec-demo/README.md`](witec-demo/README.md) (Node.js and Hardhat, Python and Flask, Sepolia testnet)

> **Known setup issue:** following `witec-demo/README.md` exactly, the contract deploy currently fails, because `TimberDemo.sol` is never copied into `contracts/`. This is being fixed.

---

## Pull requests

Open pull requests against `main`. In the description, include:

- what changed and why
- the related issue number (for example `Closes #12`)
- **what you tested and how**, using the checklists below

---

## Testing your changes

Run the checks for the part you changed.

### Plugin (`lingualinkledger/`)

- [ ] The `civDebug` variant builds without errors.
- [ ] The plugin loads in ATAK-CIV **5.4.0** on a device, and the **Lingua Link Ledger** toolbar button appears.
- [ ] **Create/Edit Data Package**: the form opens, lat/lon are pre-filled from the map center, a missing package name is rejected, and a valid entry creates a package.
- [ ] **Send Data Package**: selecting a `.zip` shows the SHA-256 hash, a hash file appears in `atak/tools/lingualinkledger/hashed_packages/`, and ATAK's Send dialog opens.

There is one placeholder unit test (`ExampleTest.java`) and no other automated tests.

### Smart contract (`witec-demo/helpers/TimberDemo.sol`)

- [ ] `npx hardhat compile` succeeds.
- [ ] The contract deploys to Sepolia.
- [ ] Through the gateway (or directly), you can create a record, verify it with a valid role, and read it back.

Contract changes need a new deployment. Records on an earlier deployment stay on that contract.

### Gateway (`witec-demo/gateway/server.py`)

Run it locally (see `witec-demo/README.md`, Part 2), then:

- [ ] `GET /health` returns `"status": "healthy"`.
- [ ] `POST /create-record` with a `0x` + 64-hex-character hash returns a `record_id`.
- [ ] `POST /verify-record` with a valid role (`logger`, `trucker`, or `processor`) succeeds.
- [ ] `GET /get-chain-of-custody?recordId=<id>` returns the record and its verifications.
- [ ] `/dashboard` loads and shows the record.

### Documentation

- [ ] Links between files work.
- [ ] Commands and file paths match the current code.

---

## Questions

Open an issue.
<!-- TODO: Add a DSL contact for questions that shouldn't be public. -->

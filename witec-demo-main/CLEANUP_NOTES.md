# Consolidation record — witec-demo-main

Prepared 2026-09-30. Read this before changing anything.

## Where this code came from

The supplied `witec-demo-main 09.15.26` archive, originally cloned from
`eternal-dissident/witec-demo`. It is the same version assessed in the
"PET Repository Technical Assessment" (`server.py` 804 lines,
`TimberDemo.sol` 165 lines — both verified).

## What was changed (complete list)

1. **Renamed the top folder** from `witec-demo-main 09.15.26` to
   `witec-demo-main`. Spaces in paths break shell scripts and some tooling.
2. **Added `.gitignore`.** The repo had none. `env.sample` is copied to `.env`
   and filled with a funded wallet's `PRIVATE_KEY`; without this file a plain
   `git add .` would have published it. `env.sample` itself stays tracked.
3. **Added this file.**

No files were removed. All ten original files are meaningful.

## Known issues carried forward, not fixed here

- **Not a runnable Hardhat project as it stands.** `guide.sh` has you run
  `npx hardhat init` and then move `hardhat.config.js` and `deploy.js` into
  place by hand. Contracts live in `helpers/`, not `contracts/`.
- **`TimberDemo.json` (the compiled ABI) is absent.** `server.py` line 58 opens
  it at startup, so the gateway will not start until it is regenerated with
  `npx hardhat compile`.
- **`CONTRACT_ADDRESS = ""`** at `server.py` line 12 — stripped before sharing.
- **Domain mismatch.** nginx here serves `retired-domain.invalid`; the plugin (commit
  `c10988a`) calls `retired-domain-2.invalid`. This repo may not be what the plugin
  actually talks to. Establish which is live before changing either.
- **Sepolia.** Network references assume Sepolia, whose published end of
  support is 30 September 2026.

See both handoff documents for the full findings register.

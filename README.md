# Doorman

She walks up to the door. Today she hands over a driving licence carrying her
name, her address, her licence number and her exact date of birth, so a stranger
can answer one question: is she over 21.

Doorman answers that question and nothing else.

- A registered issuer enrols her **once**. What lands on-chain is an opaque hash.
  No name, no address, no date of birth.
- The ID stays on her phone. It is never transmitted, encrypted or otherwise.
- At the door her phone generates a zero-knowledge proof, **on the device**, that
  she holds a valid enrolment and that the birth year inside it clears the
  threshold.
- The door learns one bit, plus a pseudonym unique to that venue.

There is no verification vendor in the middle. Nobody decrypts anything, because
nothing was ever sent.

## Why this has to be a phone

The proof is generated on the device. That is the only reason the underlying data
can stay there. Build this as a website and something has to be uploaded to a
server to be proven, and you are back to somebody holding her ID.

Kuira does on-device proving in native Rust, which is what makes this possible at
all.

## What is on-chain, and what is not

| | where |
| --- | --- |
| her name, address, document number | **never anywhere** |
| her birth year | her phone only |
| which issuer enrolled her | her phone only |
| a hash binding holder + year + issuer | on-chain, opaque |
| the fact that a proof verified | on-chain |
| a pseudonym unique to one venue | on-chain |

Every successful proof publishes exactly the same two things: a valid root of the
enrolment tree, and `true`. `checkRoot` writes its result to the public
transcript, so a value that varies between callers is a value an observer can
read. Both checks are asserted rather than returned, which makes them constant.

The venue pseudonym is stable per venue, so a bar can recognise a regular or bar
someone, but derived from the venue id, so two venues comparing notes cannot tell
they served the same person.

## The circuits

| circuit | who calls it | k | rows |
| --- | --- | --- | --- |
| `registerIssuer` | registry | 13 | 4,457 |
| `revokeIssuer` | registry | 13 | 4,457 |
| `advanceYear` | registry | 13 | 4,244 |
| `enrol` | a registered issuer | 14 | 12,689 |
| `proveOfAge` | the patron, at the door | 15 | 17,607 |

`proveOfAge` sits at k=15, which covers 32,768 rows, so there are about 15,000
rows spare. Enough to bind the full date rather than just the year without
changing the proving cost, since `k` only moves at powers of two. See
[compact-circuit-costs](https://github.com/tomiin/compact-circuit-costs).

## Three things this gets right on purpose

**One tree, not one per issuer.** Per-issuer trees would need one `checkRoot`
each, and `checkRoot` publishes its result, so an observer could read off which
issuer vouched for her. The issuer tag lives inside the leaf instead.

**The path is bound to the caller's own leaf.** `merkleTreePathRoot` hashes the
path's *own* leaf, and the witness runs on the prover's machine. Without
`assert(path.leaf == leaf)` anyone could return an enrolled person's path and be
admitted having never been enrolled. Demonstrated end to end at
[merkle-leaf-binding-probe](https://github.com/tomiin/merkle-leaf-binding-probe).

**Lying to your own witness does not work.** The birth year is inside the leaf, so
claiming a different one produces a leaf that is not in the tree. There is a test
for exactly that.

## Honest limits

1. **The issuer knows.** Enrolment means the issuer holds the link between a human
   and a commitment. Unavoidable if the credential is to mean anything, and far
   better than every venue holding a scan.
2. **Year granularity, not full date.** Someone whose birthday has not yet passed
   this year is treated as already of age. Traded for a cheaper circuit; the
   headroom to fix it is there.
3. **Nothing proves the phone is hers.** Device binding and liveness are a
   separate problem that a proof does not solve.
4. **The year is advanced by one key.** Fine for a demo, wrong for production.
5. **Unaudited.** The contract compiles and its behaviour is covered by tests.
   That is not a security review.

## Run the tests

```sh
cd contract
npm install
npm run compile
npm test
```

14 tests, covering the registry, the door, unlinkability across venues, and the
path-binding regression.

## Deployed contract

| network | address |
| --- | --- |
| Preprod | `ea2d6468eaa2143faeb30d0e618bbcef7d271e4422779656824909c1fef7d5fa` |

Deployed from the Android app itself, on a Pixel 8 emulator, with the proof
generated **on the device**. No proof server.

Circuits called on-chain, beyond the deploy:

- `claimRegistry` — records the deploying device as the registry authority
- `registerIssuer` — the registry licenses an issuer
- `enrol` — an issuer enrols a holder. Three enrolments have landed; the
  on-chain counter reads 3.

Recording of an `enrol` call running against preprod on the emulator:
[`docs/doorman-preprod-enrol.mov`](docs/doorman-preprod-enrol.mov)

## The Android app

`app/` is a Kuira SDK dApp (`io.github.kuiralabs:dapp-ui`), package
`io.github.tomiin.doorman`. Sigil identity and the embedded wallet come from the
SDK's `PanelBar`; everything below it is this project.

```sh
./gradlew :app:installDebug
```

Then: forge a sigil, switch the network picker to **preprod**, fund the wallet
address at `faucet.preprod.midnight.network`, tap **Register** next to DUST, and
deploy. The dust step has to happen in the app; the `mn` CLI cannot register dust
for an embedded wallet.

## What runs on Kuira today, and what does not

Being precise about this, because the contract in `contract/` is not identical to
the one the app currently deploys.

**Kuira 0.1.0-alpha05 cannot construct a `HistoricMerkleTree`.** A contract
declaring one fails at deploy, inside `initialState`, at
`StateValue.newBoundedMerkleTree` — before any circuit or witness runs. Neither
Kuira reference app (counter, bboard) has a tree, so nothing exercises that path.

That means the enrolment tree and `proveOfAge` are absent from the deployed
build. What the app does today is the **issuer half**: a registry, licensed
issuers, and enrolment writing one opaque hash per person. The full contract,
including the tree and the age proof, is preserved at
`contract/src/doorman.compact.full` and is what the browser build will use, since
the TypeScript SDK can query the tree for a membership path.

Four other alpha constraints found while building this, none of them documented:

1. **Every declared witness must be present** in the handle, even ones the circuit
   being called never invokes. Omit one and the constructor dies with
   `does not contain a function-valued field named <x>`.
2. **`WitnessResult` accepts only a `ByteArray`**, whatever the Compact type is.
   A `Uint<64>` witness is hand-packed bytes.
3. **`deploy()` passes no constructor arguments.** A constructor that declares a
   parameter fails with `expected 2 arguments, received 1`.
4. **The constructor cannot read witnesses.** This rules out the usual
   deployer-becomes-owner pattern, and is why `registry` is claimed by a separate
   `claimRegistry()` circuit rather than being a `sealed` field set at
   construction. The gap between deploy and claim is a race, which is an honest
   cost of the workaround.

Preprod's indexer also needs longer than the recipe's suggested 3 to 5 seconds
before a freshly deployed contract can be called; the app retries with backoff
rather than assuming a fixed wait.

---

This project is built on [Midnight](https://midnight.network), a
privacy-enhancing blockchain.

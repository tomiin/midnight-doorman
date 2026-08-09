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

Preprod: `<address to follow>`

---

This project is built on [Midnight](https://midnight.network), a
privacy-enhancing blockchain.

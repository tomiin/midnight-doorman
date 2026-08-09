// Private state + witness implementations for Doorman.
//
// Everything a holder needs lives here and never leaves the device: her key, her
// birth year, and which issuer enrolled her. The Merkle path is derived from
// public tree state at proving time, so she stores no credential blob.
import type { Ledger } from './managed/doorman/contract/index.js';

const TREE_DEPTH = 10;

export type DoormanPrivateState = {
  secretKey: Uint8Array;
  birthYear: bigint;
  // Which issuer enrolled her. Private: the door never learns it.
  issuerTag: Uint8Array;
  // Test-only. Makes the path witness return SOMEBODY ELSE'S leaf, which is what
  // a malicious prover would do since this code runs on their machine. Used by
  // the path-binding regression test.
  forgePathForLeaf?: Uint8Array;
};

export const createPrivateState = (
  secretKey: Uint8Array,
  birthYear: bigint = 0n,
  issuerTag: Uint8Array = new Uint8Array(32),
  forgePathForLeaf?: Uint8Array,
): DoormanPrivateState => ({ secretKey, birthYear, issuerTag, forgePathForLeaf });

type WitnessContext<L, PS> = { privateState: PS; ledger: L };
type PathValue = {
  leaf: Uint8Array;
  path: { sibling: { field: bigint }; goes_left: boolean }[];
};

// Well-formed but invalid. Recomputes to a root matching nothing, so checkRoot
// fails and the circuit's own assert fires with a readable message instead of
// the witness throwing.
const dummyPath = (leaf: Uint8Array): PathValue => ({
  leaf,
  path: Array.from({ length: TREE_DEPTH }, () => ({
    sibling: { field: 0n },
    goes_left: false,
  })),
});

export const witnesses = {
  localSecretKey: ({ privateState }: WitnessContext<Ledger, DoormanPrivateState>):
    [DoormanPrivateState, Uint8Array] => [privateState, privateState.secretKey],

  birthYear: ({ privateState }: WitnessContext<Ledger, DoormanPrivateState>):
    [DoormanPrivateState, bigint] => [privateState, privateState.birthYear],

  enrollingIssuer: ({ privateState }: WitnessContext<Ledger, DoormanPrivateState>):
    [DoormanPrivateState, Uint8Array] => [privateState, privateState.issuerTag],

  enrolmentPath: (
    { privateState, ledger }: WitnessContext<Ledger, DoormanPrivateState>,
    leaf: Uint8Array,
  ): [DoormanPrivateState, PathValue] => {
    const target = privateState.forgePathForLeaf ?? leaf;
    const found = (ledger.enrolled as unknown as {
      findPathForLeaf(l: Uint8Array): unknown;
    }).findPathForLeaf(target);
    return [privateState, (found as PathValue) ?? dummyPath(target)];
  },
};

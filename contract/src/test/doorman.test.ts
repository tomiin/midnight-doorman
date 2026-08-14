import { describe, it, expect } from 'vitest';
import {
  deploy, key, holderId, issuerId, enrolmentLeaf, patronId,
} from '../simulator.js';

const hex = (b: Uint8Array) => Buffer.from(b).toString('hex');

const ALICE = key('alice');
const BOB = key('bob');
const MALLORY = key('mallory');
const BAR = key('the-bar');
const CLUB = key('the-club');

// Alice is 26 in 2026. Bob is 19.
const ALICE_YEAR = 2000n;
const BOB_YEAR = 2007n;

/** Enrol someone and set them up to prove. */
const enrolWith = (
  s: ReturnType<typeof deploy>,
  name: string,
  sk: Uint8Array,
  year: bigint,
  forge?: Uint8Array,
) => {
  s.sim.as('dmv').enrol(holderId(sk), year);
  s.sim.actor(name, sk, year, s.dmvTag, forge);
};

describe('Doorman — the registry', () => {
  it('the registry can only be claimed once', () => {
    const { sim } = deploy();
    expect(() => sim.as('registry').claimRegistry())
      .toThrow(/already been claimed/);
  });

  it('registers an issuer', () => {
    const { sim, dmvTag } = deploy();
    expect(sim.isRegisteredIssuer(dmvTag)).toBe(true);
  });

  it('a stranger cannot register an issuer', () => {
    const { sim } = deploy();
    sim.actor('stranger', key('stranger'));
    expect(() => sim.as('stranger').registerIssuer(issuerId(key('rogue'))))
      .toThrow(/not the registry/);
  });

  it('an unregistered issuer cannot enrol anyone', () => {
    const { sim } = deploy();
    sim.actor('fake-dmv', key('fake-dmv'));
    expect(() => sim.as('fake-dmv').enrol(holderId(ALICE), ALICE_YEAR))
      .toThrow(/not a registered issuer/);
  });

  it('a revoked issuer can no longer enrol', () => {
    const s = deploy();
    s.sim.as('registry').revokeIssuer(s.dmvTag);
    expect(() => s.sim.as('dmv').enrol(holderId(ALICE), ALICE_YEAR))
      .toThrow(/revoked/);
  });
});

describe('Doorman — the door', () => {
  it('THE POINT: an of-age patron is admitted without revealing anything', () => {
    const s = deploy();
    enrolWith(s, 'alice', ALICE, ALICE_YEAR);
    const p = s.sim.as('alice').proveOfAge(BAR);
    expect(hex(p)).toBe(hex(patronId(ALICE, BAR)));
    expect(s.sim.getLedger().admissionCount).toBe(1n);
  });

  it('an underage patron is refused', () => {
    const s = deploy();
    enrolWith(s, 'bob', BOB, BOB_YEAR);
    expect(() => s.sim.as('bob').proveOfAge(BAR)).toThrow(/not old enough/);
  });

  it('someone never enrolled is refused', () => {
    const s = deploy();
    s.sim.actor('mallory', MALLORY, 1990n, s.dmvTag);
    expect(() => s.sim.as('mallory').proveOfAge(BAR)).toThrow(/no valid enrolment/);
  });

  it('turning 21 flips the answer, with nothing re-enrolled', () => {
    const s = deploy();
    enrolWith(s, 'bob', BOB, BOB_YEAR);
    expect(() => s.sim.as('bob').proveOfAge(BAR)).toThrow(/not old enough/);
    for (let i = 0; i < 2; i++) s.sim.as('registry').advanceYear();
    expect(() => s.sim.as('bob').proveOfAge(BAR)).not.toThrow();
  });

  it('claiming a birth year she was not enrolled with fails', () => {
    const s = deploy();
    s.sim.as('dmv').enrol(holderId(BOB), BOB_YEAR);
    // Bob lies to his own witness, claiming 1990.
    s.sim.actor('bob-liar', BOB, 1990n, s.dmvTag);
    expect(() => s.sim.as('bob-liar').proveOfAge(BAR)).toThrow(/no valid enrolment/);
  });
});

describe('Doorman — unlinkability', () => {
  it('two venues see different pseudonyms for the same person', () => {
    const s = deploy();
    enrolWith(s, 'alice', ALICE, ALICE_YEAR);
    const atBar = s.sim.as('alice').proveOfAge(BAR);
    const atClub = s.sim.as('alice').proveOfAge(CLUB);
    expect(hex(atBar)).not.toBe(hex(atClub));
  });

  it('one venue sees a stable pseudonym for a returning patron', () => {
    const s = deploy();
    enrolWith(s, 'alice', ALICE, ALICE_YEAR);
    const first = s.sim.as('alice').proveOfAge(BAR);
    const second = s.sim.as('alice').proveOfAge(BAR);
    expect(hex(first)).toBe(hex(second));
    // Counted once, not twice.
    expect(s.sim.getLedger().admissionCount).toBe(1n);
  });

  it('two people at one venue see different pseudonyms', () => {
    const s = deploy();
    enrolWith(s, 'alice', ALICE, ALICE_YEAR);
    enrolWith(s, 'carol', key('carol'), 1995n);
    const a = s.sim.as('alice').proveOfAge(BAR);
    const c = s.sim.as('carol').proveOfAge(BAR);
    expect(hex(a)).not.toBe(hex(c));
  });
});

// Regression test for the Merkle path-binding bug.
//
// merkleTreePathRoot hashes the path's OWN leaf, and the witness runs on the
// prover's machine. Without binding the returned path to the leaf the circuit
// derived, anyone could present an enrolled person's path and be admitted having
// never been enrolled. See github.com/tomiin/merkle-leaf-binding-probe.
describe('Doorman — the path must be the caller’s own', () => {
  it('rejects a path lifted from a genuinely enrolled patron', () => {
    const s = deploy();
    enrolWith(s, 'alice', ALICE, ALICE_YEAR);

    // Mallory was never enrolled. Her witness returns Alice's leaf.
    const aliceLeaf = enrolmentLeaf(holderId(ALICE), ALICE_YEAR, s.dmvTag);
    s.sim.actor('mallory', MALLORY, ALICE_YEAR, s.dmvTag, aliceLeaf);

    expect(() => s.sim.as('mallory').proveOfAge(BAR))
      .toThrow(/does not match this holder/);
  });

  it('still admits the rightful patron', () => {
    const s = deploy();
    enrolWith(s, 'alice', ALICE, ALICE_YEAR);
    expect(() => s.sim.as('alice').proveOfAge(BAR)).not.toThrow();
  });
});

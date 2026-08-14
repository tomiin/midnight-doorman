// In-memory harness around the compiled Doorman contract.
// `as(name)` swaps the acting caller, so localSecretKey() resolves to them.
import { Contract, type Ledger, ledger, pureCircuits } from './managed/doorman/contract/index.js';
import { type DoormanPrivateState, createPrivateState, witnesses } from './witnesses.js';
import {
  type CircuitContext, type CircuitResults, type ContractAddress,
  QueryContext, sampleContractAddress, createConstructorContext, CostModel,
} from '@midnight-ntwrk/compact-runtime';

const deployerCoinPublicKey = '00'.repeat(32);

// Off-chain mirrors of the contract's pure circuits.
export const holderId = (sk: Uint8Array): Uint8Array => pureCircuits.holderId(sk);
export const issuerId = (sk: Uint8Array): Uint8Array => pureCircuits.issuerId(sk);
export const enrolmentLeaf = (h: Uint8Array, y: bigint, i: Uint8Array): Uint8Array =>
  pureCircuits.enrolmentLeaf(h, y, i);
export const patronId = (sk: Uint8Array, venue: Uint8Array): Uint8Array =>
  pureCircuits.patronId(sk, venue);

export const key = (label: string): Uint8Array => {
  const b = new Uint8Array(32);
  b.set(new TextEncoder().encode(label).slice(0, 32));
  return b;
};

export class DoormanSimulator {
  readonly contract: Contract<DoormanPrivateState>;
  circuitContext: CircuitContext<DoormanPrivateState>;
  states: Record<string, DoormanPrivateState> = {};
  private update: (ps: DoormanPrivateState) => void = () => {};
  contractAddress: ContractAddress;

  constructor(registrySk: Uint8Array) {
    this.contract = new Contract<DoormanPrivateState>(witnesses);
    this.contractAddress = sampleContractAddress();
    const ps = createPrivateState(registrySk);
    const { currentPrivateState, currentContractState, currentZswapLocalState } =
      this.contract.initialState(
        createConstructorContext(ps, deployerCoinPublicKey),
      );
    this.circuitContext = {
      currentPrivateState,
      currentZswapLocalState,
      currentQueryContext: new QueryContext(currentContractState.data, this.contractAddress),
      costModel: CostModel.initialCostModel(),
    };
    this.states = { registry: currentPrivateState };
  }

  /** Register an actor so `as(name)` can act as them. */
  actor(
    name: string,
    secretKey: Uint8Array,
    birthYear: bigint = 0n,
    issuerTag: Uint8Array = new Uint8Array(32),
    forgePathForLeaf?: Uint8Array,
  ): this {
    this.states[name] = createPrivateState(secretKey, birthYear, issuerTag, forgePathForLeaf);
    return this;
  }

  as(name: string): this {
    const ps = this.states[name];
    if (!ps) throw new Error(`No private state for '${name}'.`);
    this.circuitContext = { ...this.circuitContext, currentPrivateState: ps };
    this.update = (next) => { this.states[name] = next; };
    return this;
  }

  getLedger(): Ledger { return ledger(this.circuitContext.currentQueryContext.state); }

  private commit<T>(r: CircuitResults<DoormanPrivateState, T>): T {
    this.circuitContext = r.context;
    this.update(r.context.currentPrivateState);
    return r.result;
  }

  claimRegistry(): void {
    this.commit(this.contract.impureCircuits.claimRegistry(this.circuitContext));
  }
  registerIssuer(tag: Uint8Array): void {
    this.commit(this.contract.impureCircuits.registerIssuer(this.circuitContext, tag));
  }
  revokeIssuer(tag: Uint8Array): void {
    this.commit(this.contract.impureCircuits.revokeIssuer(this.circuitContext, tag));
  }
  advanceYear(): void {
    this.commit(this.contract.impureCircuits.advanceYear(this.circuitContext));
  }
  enrol(holder: Uint8Array, year: bigint): void {
    this.commit(this.contract.impureCircuits.enrol(this.circuitContext, holder, year));
  }
  proveOfAge(venueId: Uint8Array): Uint8Array {
    return this.commit(this.contract.impureCircuits.proveOfAge(this.circuitContext, venueId));
  }
  hasBeenAdmitted(patron: Uint8Array): boolean {
    return this.commit(this.contract.impureCircuits.hasBeenAdmitted(this.circuitContext, patron));
  }
  isRegisteredIssuer(tag: Uint8Array): boolean {
    return this.commit(this.contract.impureCircuits.isRegisteredIssuer(this.circuitContext, tag));
  }
  thisYear(): bigint {
    return this.commit(this.contract.impureCircuits.thisYear(this.circuitContext));
  }
}

/** A deployed contract with the DMV already registered as an issuer. */
export const deploy = () => {
  const REGISTRY = key('registry');
  const DMV = key('dmv');
  const sim = new DoormanSimulator(REGISTRY);
  const dmvTag = issuerId(DMV);
  sim.actor('dmv', DMV);
  sim.as('registry').claimRegistry();
  sim.as('registry').registerIssuer(dmvTag);
  return { sim, dmvTag, DMV };
};

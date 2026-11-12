//// A nullifier registry: one UTxO per deployment, at the validator's own address, holding the
//// deployment's `registry` token and the root of an on-chain MPF of everything already claimed.
////
//// A claim must insert its nullifier with `mpf.insert`, which only succeeds when the proof shows the
//// key is absent. So the same fact can never be claimed twice, and the trie never needs to be on chain.

use aiken/collection/list
use aiken/merkle_patricia_forestry as mpf
use cardano/address.{Address, Script}
use cardano/assets.{PolicyId, lovelace_of, quantity_of}
use cardano/transaction.{InlineDatum, Input, Output, OutputReference, Transaction}

pub const registry_token = "registry"

/// The spent registry input and the single continuing registry output, both checked to hold the token.
pub fn continuing(
  self: Transaction,
  own_ref: OutputReference,
  policy: PolicyId,
) -> (Output, Output) {
  expect Some(own) = list.find(self.inputs, fn(i) { i.output_reference == own_ref })
  expect quantity_of(own.output.value, policy, registry_token) == 1
  expect [next] =
    list.filter(self.outputs, fn(o) { o.address == own.output.address })
  expect quantity_of(next.value, policy, registry_token) == 1
  expect lovelace_of(next.value) >= lovelace_of(own.output.value)
  (own.output, next)
}

/// The inline datum of an output.
pub fn inline(output: Output) -> Data {
  expect InlineDatum(data) = output.datum
  data
}

/// Insert a nullifier into a claimed-set root, returning the new root (fails if already present).
pub fn claim(
  claimed_root: ByteArray,
  nullifier: ByteArray,
  value: ByteArray,
  proof: mpf.Proof,
) -> ByteArray {
  mpf.from_root(claimed_root) |> mpf.insert(nullifier, value, proof) |> mpf.root
}

/// The registry input being spent in this transaction, if any.
pub fn is_spent(self: Transaction, policy: PolicyId) -> Bool {
  list.any(
    self.inputs,
    fn(i) {
      and {
        i.output.address.payment_credential == Script(policy),
        quantity_of(i.output.value, policy, registry_token) == 1,
      }
    },
  )
}

/// The script address of a policy (multi-handler validators share one hash for mint and spend).
pub fn own_address(policy: PolicyId) -> Address {
  Address { payment_credential: Script(policy), stake_credential: None }
}

/// Exactly one output to the registry address carrying the token, with the given inline datum.
pub fn initialised(self: Transaction, policy: PolicyId, datum: Data) -> Bool {
  expect [out] =
    list.filter(
      self.outputs,
      fn(o) { o.address.payment_credential == Script(policy) },
    )
  and {
    quantity_of(out.value, policy, registry_token) == 1,
    out.datum == InlineDatum(datum),
  }
}

/// The seed output reference is spent, making the registry token one-shot.
pub fn seed_spent(self: Transaction, seed: OutputReference) -> Bool {
  list.any(self.inputs, fn(i) { i.output_reference == seed })
}

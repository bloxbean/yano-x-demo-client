//// Read a Yano app chain's script anchor from a reference input.
////
//// The anchor UTxO sits at the anchor validator and carries the chain's one-shot thread token. Its inline
//// datum is the 11-field AnchorDatum (Yano core-api anchor-v1.cddl), advanced only when a threshold of the
//// chain's members co-sign. Only the latest anchored root is on chain, so proofs must be built at
//// `datum.height`.

use aiken/collection/dict
use aiken/collection/list
use cardano/address.{Script}
use cardano/assets.{PolicyId, tokens}
use cardano/transaction.{InlineDatum, Input, Transaction}

/// Copied field-for-field from Yano's appchain-anchor/validators/anchor.ak (ABI order).
pub type AnchorDatum {
  version: Int,
  chain_id: ByteArray,
  chain_genesis_id: ByteArray,
  application_id: ByteArray,
  commitment_profile_id: ByteArray,
  format_fingerprint: ByteArray,
  height: Int,
  block_hash: ByteArray,
  state_root: ByteArray,
  member_keys: List<ByteArray>,
  threshold: Int,
}

/// Which anchor a validator trusts: the anchor script, its thread token policy and the chain identity.
pub type AnchorRef {
  script_hash: ByteArray,
  thread_policy: PolicyId,
  chain_id: ByteArray,
  genesis_id: ByteArray,
}

/// The one reference input holding the chain's anchor thread token at the anchor script, decoded and
/// checked against the expected chain identity and the MPF commitment profile.
pub fn anchored(self: Transaction, anchor: AnchorRef) -> AnchorDatum {
  expect [input] = list.filter(self.reference_inputs, is_anchor(_, anchor))
  expect InlineDatum(data) = input.output.datum
  expect datum: AnchorDatum = data
  expect and {
      datum.version == 1,
      datum.chain_id == anchor.chain_id,
      datum.chain_genesis_id == anchor.genesis_id,
      datum.commitment_profile_id == "mpf-blake2b256-v1",
    }
  datum
}

fn is_anchor(input: Input, anchor: AnchorRef) -> Bool {
  and {
    input.output.address.payment_credential == Script(anchor.script_hash),
    !dict.is_empty(tokens(input.output.value, anchor.thread_policy)),
  }
}

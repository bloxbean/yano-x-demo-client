//// Certified release NFT.
////
//// A document released on the Yano `document-review` chain (an issuer proposed it, two auditors from
//// different organisations approved it, and the release consumed that approval exactly once) leaves a
//// release receipt in the chain's state. Anyone may mint one NFT per release by proving that receipt
//// against the chain's script-anchored state root. The proposal id is the nullifier: it is inserted into
//// the registry's claimed-set trie, so a second mint for the same release fails.

use aiken/collection/dict
use aiken/collection/list
use aiken/merkle_patricia_forestry as mpf
use aiken/primitive/bytearray
use cardano/address.{Script}
use cardano/assets.{PolicyId, tokens}
use cardano/transaction.{OutputReference, Transaction}
use yano/anchor.{AnchorRef}
use yano/records.{ReleaseReceipt}
use yano/registry

pub type CertificatesDatum {
  claimed_root: ByteArray,
}

pub type CertificatesMint {
  Open
  Certify
}

pub type CertifyClaim {
  receipt: ReleaseReceipt,
  proof: mpf.Proof,
  nullifier_proof: mpf.Proof,
}

validator certificates(
  anchor: AnchorRef,
  release_policy: ByteArray,
  seed: OutputReference,
) {
  mint(redeemer: CertificatesMint, policy_id: PolicyId, self: Transaction) {
    when redeemer is {
      Open -> {
        let empty: Data =
          CertificatesDatum { claimed_root: mpf.root(mpf.empty) }
        and {
          registry.seed_spent(self, seed),
          dict.to_pairs(tokens(self.mint, policy_id)) == [
            Pair(registry.registry_token, 1),
          ],
          registry.initialised(self, policy_id, empty),
        }
      }
      // Every check lives in the registry's spend handler, which must run in the same transaction.
      Certify -> registry.is_spent(self, policy_id)
    }
  }

  spend(
    datum: Option<CertificatesDatum>,
    redeemer: CertifyClaim,
    own_ref: OutputReference,
    self: Transaction,
  ) {
    expect Some(current) = datum
    expect Some(own) =
      list.find(self.inputs, fn(i) { i.output_reference == own_ref })
    expect Script(policy) = own.output.address.payment_credential
    let (_, next) = registry.continuing(self, own_ref, policy)
    let receipt = redeemer.receipt
    let anchored = anchor.anchored(self, anchor)
    let next_datum: Data =
      CertificatesDatum {
        claimed_root: registry.claim(
          current.claimed_root,
          receipt.proposal_id,
          receipt.action_commitment,
          redeemer.nullifier_proof,
        ),
      }
    and {
      // The fact: this exact receipt is in the anchored state of the document-review chain.
      mpf.has(
        mpf.from_root(anchored.state_root),
        records.release_receipt_key(receipt.proposal_id),
        records.release_receipt_value(receipt),
        redeemer.proof,
      ),
      // Only releases under the configured approval policy count.
      receipt.policy_id == release_policy,
      // The NFT is named after the proposal; one per release.
      bytearray.length(receipt.proposal_id) <= 32,
      receipt.proposal_id != registry.registry_token,
      dict.to_pairs(tokens(self.mint, policy)) == [Pair(receipt.proposal_id, 1)],
      // The nullifier: the claimed-set root advances by exactly this proposal id.
      registry.inline(next) == next_datum,
    }
  }

  else(_) {
    fail
  }
}

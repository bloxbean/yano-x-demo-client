//// Snapshot rewards from the Yano `balances` chain.
////
//// Opening a season copies the chain's currently anchored root and height into the registry datum, checked
//// on-chain against the anchor reference input. Every claim then proves an account's balance against that
//// frozen snapshot, so claims don't race later anchors. The claim mints that many POINTS and sends them to
//// the Cardano address of the account's own key (a balances account is a member's Ed25519 key, and
//// blake2b-224 of it is a Cardano key hash). Anyone may pay the fee; only the account owner can spend the
//// tokens. The account key is the nullifier: one claim per account per season.

use aiken/collection/dict
use aiken/collection/list
use aiken/crypto.{blake2b_224}
use aiken/merkle_patricia_forestry as mpf
use aiken/primitive/bytearray
use cardano/address.{Script, VerificationKey}
use cardano/assets.{PolicyId, quantity_of, tokens}
use cardano/transaction.{OutputReference, Transaction}
use yano/anchor.{AnchorRef}
use yano/cbor
use yano/records
use yano/registry

pub const reward_token = "POINTS"

pub type RewardsDatum {
  snapshot_height: Int,
  snapshot_root: ByteArray,
  claimed_root: ByteArray,
}

pub type RewardsMint {
  Open
  Reward
}

pub type RewardClaim {
  account: ByteArray,
  amount: Int,
  proof: mpf.Proof,
  nullifier_proof: mpf.Proof,
}

validator rewards(anchor: AnchorRef, seed: OutputReference) {
  mint(redeemer: RewardsMint, policy_id: PolicyId, self: Transaction) {
    when redeemer is {
      Open -> {
        let anchored = anchor.anchored(self, anchor)
        let season: Data =
          RewardsDatum {
            snapshot_height: anchored.height,
            snapshot_root: anchored.state_root,
            claimed_root: mpf.root(mpf.empty),
          }
        and {
          registry.seed_spent(self, seed),
          dict.to_pairs(tokens(self.mint, policy_id)) == [
            Pair(registry.registry_token, 1),
          ],
          registry.initialised(self, policy_id, season),
        }
      }
      Reward -> registry.is_spent(self, policy_id)
    }
  }

  spend(
    datum: Option<RewardsDatum>,
    redeemer: RewardClaim,
    own_ref: OutputReference,
    self: Transaction,
  ) {
    expect Some(current) = datum
    expect Some(own) =
      list.find(self.inputs, fn(i) { i.output_reference == own_ref })
    expect Script(policy) = own.output.address.payment_credential
    let (_, next) = registry.continuing(self, own_ref, policy)
    let RewardClaim { account, amount, proof, nullifier_proof } = redeemer
    let owner = VerificationKey(blake2b_224(account))
    let next_datum: Data =
      RewardsDatum {
        ..current,
        claimed_root: registry.claim(
          current.claimed_root,
          account,
          cbor.uint(amount),
          nullifier_proof,
        ),
      }
    and {
      bytearray.length(account) == 32,
      amount > 0,
      // The fact: this account held exactly `amount` at the snapshot height.
      mpf.has(
        mpf.from_root(current.snapshot_root),
        records.balance_key(account),
        records.balance_value(amount),
        proof,
      ),
      dict.to_pairs(tokens(self.mint, policy)) == [Pair(reward_token, amount)],
      // The tokens go to the account owner's own key, whoever submits the claim.
      list.any(
        self.outputs,
        fn(o) {
          and {
            o.address.payment_credential == owner,
            quantity_of(o.value, policy, reward_token) >= amount,
          }
        },
      ),
      // The nullifier: one claim per account in this season.
      registry.inline(next) == next_datum,
    }
  }

  else(_) {
    fail
  }
}

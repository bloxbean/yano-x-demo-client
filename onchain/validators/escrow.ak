//// Delivery escrow on the Yano `orders-chain`.
////
//// A buyer locks ADA for an order. It is released to the supplier when someone proves that a
//// "delivered" event for that order was finalized on the order log: the validator rebuilds the event's
//// message id from its envelope fields, rebuilds the finalized-message record the ordered-log keeps for
//// it, and checks that record against the chain's script-anchored root. Optionally only one member (the
//// carrier's node) may have sent the event. The escrow UTxO is spent once, so no separate nullifier is
//// needed. After the deadline the buyer can take the funds back.

use aiken/collection/list
use aiken/interval
use aiken/merkle_patricia_forestry as mpf
use aiken/primitive/bytearray
use cardano/address.{VerificationKey}
use cardano/assets.{lovelace_of}
use cardano/transaction.{OutputReference, Transaction}
use yano/anchor.{AnchorRef}
use yano/records

pub const order_topic = "orders.command.v1"

pub type EscrowDatum {
  order_id: ByteArray,
  supplier: ByteArray,
  buyer: ByteArray,
  carrier: Option<ByteArray>,
  refund_after: Int,
}

pub type EscrowAction {
  Release {
    sender: ByteArray,
    sender_seq: Int,
    expires_at: Int,
    body: ByteArray,
    height: Int,
    index: Int,
    proof: mpf.Proof,
  }
  Refund
}

validator escrow(anchor: AnchorRef) {
  spend(
    datum: Option<EscrowDatum>,
    redeemer: EscrowAction,
    own_ref: OutputReference,
    self: Transaction,
  ) {
    expect Some(d) = datum
    expect Some(own) =
      list.find(self.inputs, fn(i) { i.output_reference == own_ref })
    // One escrow per transaction, so one supplier payment can't satisfy two escrows.
    expect [_] =
      list.filter(
        self.inputs,
        fn(i) {
          i.output.address.payment_credential == own.output.address.payment_credential
        },
      )
    when redeemer is {
      Release { sender, sender_seq, expires_at, body, height, index, proof } -> {
        let anchored = anchor.anchored(self, anchor)
        let id =
          records.message_id(
            anchored.chain_id,
            order_topic,
            sender,
            sender_seq,
            expires_at,
            body,
          )
        and {
          delivered(body, d.order_id),
          when d.carrier is {
            Some(carrier) -> carrier == sender
            None -> True
          },
          // The fact: this exact message was finalized at (height, index), under the anchored root.
          mpf.has(
            mpf.from_root(anchored.state_root),
            records.finalized_message_key(id),
            records.finalized_message_value(height, index, order_topic, sender),
            proof,
          ),
          paid_to(self, d.supplier) >= lovelace_of(own.output.value),
        }
      }
      Refund ->
        and {
          list.has(self.extra_signatories, d.buyer),
          interval.is_entirely_after(self.validity_range, d.refund_after),
        }
    }
  }

  else(_) {
    fail
  }
}

/// The demo client writes `{"event":"delivered"[,"note":"…"],"order":"<id>"}` (keys sorted, quotes in
/// the note escaped), so the order id is the last field.
pub fn delivered(body: ByteArray, order_id: ByteArray) -> Bool {
  let prefix = "{\"event\":\"delivered\""
  let suffix = bytearray.concat(bytearray.concat(",\"order\":\"", order_id), "\"}")
  let n = bytearray.length(body)
  and {
    n >= bytearray.length(prefix) + bytearray.length(suffix),
    bytearray.take(body, bytearray.length(prefix)) == prefix,
    bytearray.drop(body, n - bytearray.length(suffix)) == suffix,
  }
}

fn paid_to(self: Transaction, key_hash: ByteArray) -> Int {
  list.foldl(
    self.outputs,
    0,
    fn(o, sum) {
      if o.address.payment_credential == VerificationKey(key_hash) {
        sum + lovelace_of(o.value)
      } else {
        sum
      }
    },
  )
}

test delivered_matches() {
  and {
    delivered("{\"event\":\"delivered\",\"order\":\"A-1042\"}", "A-1042"),
    delivered(
      "{\"event\":\"delivered\",\"note\":\"left at dock 4\",\"order\":\"A-1042\"}",
      "A-1042",
    ),
    !delivered("{\"event\":\"shipped\",\"order\":\"A-1042\"}", "A-1042"),
    !delivered("{\"event\":\"delivered\",\"order\":\"A-10420\"}", "A-1042"),
    !delivered("{\"event\":\"delivered\",\"order\":\"B-1042\"}", "A-1042"),
  }
}

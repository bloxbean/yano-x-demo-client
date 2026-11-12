//// A real release receipt from document-review-chain, rebuilt on-chain and proven against the anchored root.

use aiken/merkle_patricia_forestry as mpf
use vectors/receipt as v
use yano/records.{ReleaseReceipt}

test receipt_key_rebuilds() {
  records.release_receipt_key(v.receipt().proposal_id) == v.state_key
}

test receipt_value_rebuilds() {
  records.release_receipt_value(v.receipt()) == v.state_value
}

test receipt_proven_against_anchor() {
  mpf.has(
    mpf.from_root(v.state_root),
    records.release_receipt_key(v.receipt().proposal_id),
    records.release_receipt_value(v.receipt()),
    v.proof(),
  )
}

test other_policy_fails() fail {
  expect
    mpf.has(
      mpf.from_root(v.state_root),
      records.release_receipt_key(v.receipt().proposal_id),
      records.release_receipt_value(ReleaseReceipt { ..v.receipt(), policy_id: "fast-track" }),
      v.proof(),
    )
}

//// Spike: real bytes from the public showcase, checked with the official Aiken MPF library.

use aiken/merkle_patricia_forestry as mpf
use vectors/orders as v
use yano/records

test message_id_rebuilds() {
  records.message_id(v.chain_id, v.topic, v.sender, v.sender_seq, v.expires_at, v.body) == v.message_id
}

test record_rebuilds() {
  and {
    records.finalized_message_key(v.message_id) == v.state_key,
    records.finalized_message_value(v.block_height, v.message_index, v.topic, v.sender) == v.state_value,
  }
}

test inclusion_against_anchored_root() {
  mpf.has(mpf.from_root(v.state_root), v.state_key, v.state_value, v.proof())
}

test tampered_value_fails() fail {
  expect
    mpf.has(
      mpf.from_root(v.state_root),
      v.state_key,
      records.finalized_message_value(v.block_height, 1, v.topic, v.sender),
      v.proof(),
    )
}

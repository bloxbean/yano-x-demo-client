//// Yano app-ledger keys and values, rebuilt on-chain from typed fields.
////
//// Sources (Yano and Yano X `main`):
//// - envelope v2 signed body and message id: core-api AppMessage (BLAKE2b-256 of the signed body)
//// - finalized-message index: core-api FinalizedMessageIndex (used by ordered-log)
//// - composite component keys: composition CompositeCommitmentV1.componentKey
//// - document-review release receipt: showcase DocumentReviewReceiptV1
//// - balances: stdlib BalancesStateMachine (`b/<account hex>` -> BigInteger bytes)

use aiken/builtin
use aiken/crypto.{blake2b_256, sha2_256}
use aiken/primitive/bytearray
use yano/cbor

/// An app-message envelope's identity: BLAKE2b-256 of
/// `[chainId, topic, sender, senderSeq, expiresAt, body]`.
pub fn message_id(
  chain_id: ByteArray,
  topic: ByteArray,
  sender: ByteArray,
  sender_seq: Int,
  expires_at: Int,
  body: ByteArray,
) -> ByteArray {
  blake2b_256(
    cbor.array(
      [
        cbor.tstr(chain_id), cbor.tstr(topic), cbor.bstr(sender),
        cbor.uint(sender_seq), cbor.uint(expires_at), cbor.bstr(body),
      ],
    ),
  )
}

/// State key of the finalized-message record: `sha256("~yano/finalized-message/v1/" || messageId)`.
pub fn finalized_message_key(message_id: ByteArray) -> ByteArray {
  sha2_256(bytearray.concat("~yano/finalized-message/v1/", message_id))
}

/// State value of the finalized-message record: `[1, height, index, topic, sender]`.
pub fn finalized_message_value(
  height: Int,
  index: Int,
  topic: ByteArray,
  sender: ByteArray,
) -> ByteArray {
  cbor.array(
    [
      cbor.uint(1), cbor.uint(height), cbor.uint(index), cbor.tstr(topic),
      cbor.bstr(sender),
    ],
  )
}

/// Physical key of a composite component's local key:
/// `"yano-composite-state-v1\0" || u8 len(id) || id || u16 len(local) || local`.
pub fn component_key(component_id: ByteArray, local_key: ByteArray) -> ByteArray {
  bytearray.concat(
    #"79616e6f2d636f6d706f736974652d73746174652d763100",
    bytearray.concat(
      builtin.integer_to_bytearray(True, 1, bytearray.length(component_id)),
      bytearray.concat(
        component_id,
        bytearray.concat(
          builtin.integer_to_bytearray(True, 2, bytearray.length(local_key)),
          local_key,
        ),
      ),
    ),
  )
}

/// A document-review release receipt, written once when an approval is consumed by a release.
pub type ReleaseReceipt {
  proposal_id: ByteArray,
  entity_id: ByteArray,
  action_commitment: ByteArray,
  policy_id: ByteArray,
  policy_revision: Int,
  applied_height: Int,
  message_id: ByteArray,
}

pub fn release_receipt_key(proposal_id: ByteArray) -> ByteArray {
  component_key("document-review-receipts", bytearray.concat("approval/", proposal_id))
}

pub fn release_receipt_value(r: ReleaseReceipt) -> ByteArray {
  cbor.array(
    [
      cbor.uint(1), cbor.tstr(r.proposal_id), cbor.tstr(r.entity_id),
      cbor.bstr(r.action_commitment), cbor.tstr(r.policy_id),
      cbor.uint(r.policy_revision), cbor.uint(r.applied_height),
      cbor.bstr(r.message_id),
    ],
  )
}

/// Balances key: `"b/" || lowercase hex of the 32-byte member key`.
pub fn balance_key(account: ByteArray) -> ByteArray {
  bytearray.concat("b/", hex(account))
}

/// Balances value: the amount as Java `BigInteger.toByteArray()` (big-endian two's complement).
/// Only positive balances are stored, so the minimal encoding is the shortest big-endian form
/// with a leading zero byte when the top bit would otherwise be set.
pub fn balance_value(amount: Int) -> ByteArray {
  expect amount > 0
  let raw = minimal_be(amount, #"")
  if bytearray.at(raw, 0) >= 128 {
    bytearray.push(raw, 0)
  } else {
    raw
  }
}

fn minimal_be(n: Int, acc: ByteArray) -> ByteArray {
  if n == 0 {
    acc
  } else {
    minimal_be(n / 256, bytearray.push(acc, n % 256))
  }
}

/// Lowercase hex of bytes, as UTF-8 bytes.
pub fn hex(b: ByteArray) -> ByteArray {
  bytearray.foldr(
    b,
    #"",
    fn(byte, acc) {
      acc
        |> bytearray.push(digit(byte % 16))
        |> bytearray.push(digit(byte / 16))
    },
  )
}

fn digit(n: Int) -> Int {
  if n < 10 {
    n + 48
  } else {
    n + 87
  }
}

test balance_encoding() {
  and {
    balance_value(200) == #"00c8",
    balance_value(100) == #"64",
    balance_value(65535) == #"00ffff",
    hex(#"0aff") == "0aff",
  }
}

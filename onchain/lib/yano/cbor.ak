//// Canonical CBOR encoding (RFC 8949 §4.2.1), just enough to rebuild Yano records on-chain.
////
//// Yano stores app-ledger values as canonical CBOR that may contain text strings, which are not valid
//// Plutus Data. Instead of decoding those bytes, a validator rebuilds the exact bytes from typed fields
//// and proves them: if any field differs, the bytes differ and the MPF proof fails.

use aiken/builtin
use aiken/primitive/bytearray

/// The initial bytes of a data item of `major` type with argument `n`, shortest form.
pub fn head(major: Int, n: Int) -> ByteArray {
  let m = major * 32
  if n < 24 {
    #[] |> bytearray.push(m + n)
  } else if n < 256 {
    builtin.integer_to_bytearray(True, 1, n) |> bytearray.push(m + 24)
  } else if n < 65536 {
    builtin.integer_to_bytearray(True, 2, n) |> bytearray.push(m + 25)
  } else if n < 4294967296 {
    builtin.integer_to_bytearray(True, 4, n) |> bytearray.push(m + 26)
  } else {
    builtin.integer_to_bytearray(True, 8, n) |> bytearray.push(m + 27)
  }
}

/// An unsigned integer (major type 0).
pub fn uint(n: Int) -> ByteArray {
  expect n >= 0
  head(0, n)
}

/// A byte string (major type 2).
pub fn bstr(b: ByteArray) -> ByteArray {
  bytearray.concat(head(2, bytearray.length(b)), b)
}

/// A text string (major type 3). The caller supplies UTF-8 bytes.
pub fn tstr(b: ByteArray) -> ByteArray {
  bytearray.concat(head(3, bytearray.length(b)), b)
}

/// A definite-length array of already-encoded items (major type 4).
pub fn array(items: List<ByteArray>) -> ByteArray {
  do_array(items, 0, #"")
}

fn do_array(items: List<ByteArray>, count: Int, acc: ByteArray) -> ByteArray {
  when items is {
    [] -> bytearray.concat(head(4, count), acc)
    [x, ..rest] -> do_array(rest, count + 1, bytearray.concat(acc, x))
  }
}

test head_forms() {
  and {
    uint(0) == #"00",
    uint(23) == #"17",
    uint(24) == #"1818",
    uint(500) == #"1901f4",
    uint(1794297270980) == #"1b000001a1c47392c4",
    tstr("ab") == #"626162",
    array([uint(1), uint(9)]) == #"820109",
  }
}

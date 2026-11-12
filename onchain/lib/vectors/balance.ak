//// Generated from the public showcase (balances-chain). Real bytes; do not edit.
use aiken/merkle_patricia_forestry.{Branch, Proof}

pub const anchored_height = 31
pub const state_root = #"563ec685f02438a1907296ac624807eeae1f530ab369748382afb86050fc2461"
pub const account = #"40c91b44da6d63657ad1102187c4df2f89ce74ee8d141502940a9644fd2fc227"
pub const amount = 69
pub const value = #"45"

pub fn proof() -> Proof {
  [
    Branch { skip: 0, neighbors: #"65ddf6b4fb9b0e5c7ebe7bee65f3373acee01a5b69e43baa532609e575a4b73d4111baab616363ec606333f0da38668d28a0af408085910b5dfac34d75df2779e1b4c248537836d60d8b687cb1a0932ff528c0393deb0745389e5ab70b64182b8a5df17ceaba142233770ba62fb7f962646488b5d8fe51a4f557e006265f3f11" },
    Branch { skip: 0, neighbors: #"8bc07adbf65469c47571519d1872bc1539a784017dbbd4e179338612a0ed41b385c09af929492a871e4fae32d9d5c36e352471cd659bcdb61de08f1722acc3b10eb923b0cbd24df54401d998531feead35a47a99f4deed205de4af81120f9761575160c10ee66ec4093ee1bca89f708ee3a914fcfda32406ea32f7e22d2d20ae" },
  ]
}

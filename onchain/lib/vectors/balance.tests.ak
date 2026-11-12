//// A real member balance from balances-chain, rebuilt and proven against the anchored root.

use aiken/merkle_patricia_forestry as mpf
use vectors/balance as v
use yano/records

test balance_value_rebuilds() {
  records.balance_value(v.amount) == v.value
}

test balance_proven_against_anchor() {
  mpf.has(mpf.from_root(v.state_root), records.balance_key(v.account), records.balance_value(v.amount), v.proof())
}

test inflated_balance_fails() fail {
  expect
    mpf.has(mpf.from_root(v.state_root), records.balance_key(v.account), records.balance_value(v.amount + 1), v.proof())
}

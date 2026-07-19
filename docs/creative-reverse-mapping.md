# Creative reverse mapping status

Runtime creative reverse mapping is fail-closed in 0.3 Beta. The default is
`creative_reverse_mapping_enabled=false`; setting it true aborts startup with a
clear error. Therefore `runCreativePlaytest` is intentionally not registered
and no creative security success is claimed.

Existing pure validation code rejects malformed/forged mapping markers, but it
is not connected to the live creative packet path. A future implementation
requires server-issued authenticated capabilities, target/component allowlists,
expiry, replay protection, rate limits, single-use semantics, connection and
client-profile binding, key rotation, and real attack tests. Polymer's unsigned
reverse payload is not accepted as a substitute.

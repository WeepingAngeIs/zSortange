# Isolated failed-write repair experiment

Not a production feature or installable release. Preserve beta.6 and all production schemas. Latest experiment starts from the exact verified backpressure checkpoint, not a competing mod.

Proposed boundary: immutable full chunk snapshot -> checksummed world-bound sidecar publication with file force, atomic rename and directory force -> original region write -> original region force -> read-back -> completion proof. The sidecar is experimental extra data in disposable worlds, never a change to the existing region/ledger/player formats. Unsupported filesystem durability must fail closed.

Ordinary asynchronous writes are intercepted at the actual IO worker, never by moving live world serialization to a background thread. Supported pocket storage is quarantined from submission through confirmation and across failure; unrelated storage and forced-return recovery remain available. No deposit or withdrawal command is replayed. Repair restores a full snapshot only if its exact token and room identity still match and its chunk is not loaded. Retries are bounded; safe reset requires explicit verified proof and another read-back, not deleting pending data.

A snapshot not yet durably journaled cannot be promised after power loss. A failed journal and failed primary store cannot manufacture a copy. Existing player/region/POI/ledger non-atomicity is not solved. Exact experiments, limits and failures will be reported after execution, not assumed from this design.

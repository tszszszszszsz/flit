# NoiseBudget

NoiseBudget validates a BLE announce/scan schedule against a duty-cycle
ceiling supplied by the caller. The schedule describes one repeating cycle in
milliseconds and a list of windows during which the radio is described as
active. Each window carries a kind (announce or scan), a start offset within
the cycle, and a duration. The ceiling is given in basis points, where 10,000
basis points means 100 percent of the cycle. The result states whether the
schedule fits, how much of the cycle is occupied, and why it fails.

The occupied time is the union of the accepted windows. Two windows that
overlap count as one occupied span, because the schedule models a single radio
that cannot be in two states at once for the same instant. Overlap is removed
by sorting the windows by start offset and merging them before summing their
lengths. Adjacent windows therefore produce the same total whether they are
supplied as one longer window or as two touching ones.

Inputs and bounds
-----------------

Three values are supplied to `validate`: the cycle length, the ceiling, and
the list of windows. The cycle length must be between 1 and 86,400,000
milliseconds. The ceiling must be between 0 and 10,000 basis points. At most 64
windows are examined; any additional windows are reported and ignored, so the
work done and the size of the result do not depend on the size of the supplied
list. Each window must have a non-negative start, a non-negative duration, a
start strictly inside the cycle, and a duration that fits before the cycle
ends. A zero-duration window is accepted and contributes nothing.

Malformed input is data, not an error path. A bad cycle length or ceiling
produces an invalid report with zeroed figures and a reason. A malformed
window produces a finding for that window at its input index and is excluded
from the occupied-time sum, while the remaining windows are still evaluated.
The function does not throw for any values of its parameters, including
extreme positive and negative 64-bit numbers.

Determinism and arithmetic
--------------------------

All arithmetic is integer arithmetic. No floating point is used, so results
are reproducible across platforms. The allowed number of active milliseconds
is the floor of `cycle * ceiling / 10000`. The verdict uses cross
multiplication, `active * 10000 > cycle * ceiling`, so a truncation in the
allowed-milliseconds value cannot change the outcome. Within the stated
bounds, every product fits in a 64-bit signed integer and the divisor is never
zero. Findings are ordered by input index, with a single cycle-wide budget
finding appended last, and the human-readable explanations follow that same
order. Equal arguments always produce equal results, and permuting the input
windows does not change the occupied-time total.

Invariants
----------

For a valid cycle and ceiling, the active milliseconds lie between zero and
the cycle length, and the used basis points lie between zero and 10,000. A
schedule is marked OK only when it is structurally valid and its occupied time
is within the ceiling. Structural problems take precedence: an invalid
schedule is reported as invalid even if it is also over budget. The report
carries the accepted window count split by kind, the structured findings, the
explanations, and a single-line summary, all derived from the same computation.

Limitations
-----------

The module is arithmetic over supplied numbers. It performs no radio
measurement, makes no claim about how a radio stack behaves, and does not
schedule, start, or stop any scan or announce. It has no clock, no
notion of real elapsed time, and no knowledge of regulatory limits; the
ceiling is whatever the caller provides. It assumes a single shared radio, so
it cannot represent simultaneous independent transceivers. It does not read or
write files, use sockets, or depend on Android classes. Validation is
synchronous and holds no state between calls.

`FlitBle.validateDutySchedule` wraps this core and
`FlitBle.auditDutySchedule` logs its findings at engine start. The report
never steers the radio: duty phases stay unchanged when a schedule is
rejected.

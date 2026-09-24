# Bathroom Fans

`BathroomFanParent.groovy` - the container. Owns nothing: creates / lists / renames children and
shows one status line per child. `BathroomFanChild.groovy` - one bathroom: excess-over-house
trigger with local confirmation, bounded turn-off target (max of resting+margin, floor), run log,
learned-table persistence with provenance.

**The child is GENERATED.** `BathroomFanNG.groovy` is the source (the standalone form of the same
app); `make_child.py` derives the child from it, changing only the parent declaration, the code
defaults (set to the values measured on the master bathroom over 38 runs), the importUrl and the
version - and refuses to write unless the core decision block is byte-identical. Edit the
standalone, run `make_child.py`, push the child. Never hand-edit the child.

Design record, evidence and the migration procedure live in the working folder, not here:
`C:\CLAUDE\Hubitat\NextGen\README.md` (sections 2.9i and 2.9n) and `MIGRATION-2026-09-24.md`.

// Which bundle this code is installed as, read off its own location.
//
// The kit ships twice — `.claude` and `.opencode` — and every file that spelled the directory name
// became a file that has to be path-adapted forever, with a parity test to keep the adaptation from
// growing into real drift. Five of the hook sources spelled it: the state directory, the shell
// perimeter's exception for that directory, every refusal message naming the command to run next, the
// doctor's fallback and the batch runner's state path.
//
// Spelling it once, derived, costs three lines and removes the whole category. The hooks are then
// byte-identical between the copies: `BundleParityTest` compares them directly, and an edit made in
// one is an edit made in both or a failing build.
//
// This is also what makes the second copy honest. Before, `.opencode/` carried eighteen references to
// `hooks/stand-guard.mjs` and no `hooks/` directory — the kb-lookup skill told the model to establish
// something "MECHANICALLY, never from memory" with a command that was not there. The guard is plain
// Node and host-agnostic; what is Claude-specific is the WIRING (settings.json events), not the code.

import { basename, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

/** `.claude` or `.opencode` — the directory the bundle was installed as. */
export const BUNDLE = basename(dirname(dirname(dirname(fileURLToPath(import.meta.url)))));

/** The guard as a command line names it, for the "run this next" half of every refusal. */
export const GUARD = `${BUNDLE}/hooks/stand-guard.mjs`;

/** Per-run bookkeeping: an artefact of one session in one installation, never of the kit. */
export const STATE_DIR = `${BUNDLE}/.stand-test`;

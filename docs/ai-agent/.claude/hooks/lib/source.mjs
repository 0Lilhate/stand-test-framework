// Two views of a Java source file, for questions that are about CODE rather than about text.
//
// The detectors and finding 18 both ask such questions — which builder step is this, does it declare
// its key, how many assertions are there — and both used to ask them of the raw file. The answers
// then came from anywhere: `// TODO: add .whereTestRunId(...)` in a comment supplied a missing call,
// and `assertThat(x)` turned into `// assertThat(x)` still counted as a check, which is the whole of
// "make the red test green" done in one keystroke.
//
// Blanking rather than deleting is what makes this usable: every offset in a projection equals the
// offset in the original, so a match found in one can be read against the other. Both views come out
// of one pass, because both callers want one and the pass is the expensive part.
//
// This module exists rather than a helper inside `scan.mjs` because `scan.mjs` imports `conceal.mjs`,
// so the reverse edge would be a cycle. It is deliberately dependency-free.

/**
 * Not a Java lexer, and it does not need to be. Every construct it does not model degrades toward
 * blanking MORE than it should, which loses a finding's evidence but never invents one: a sanction
 * the caller cannot see is a finding, and an assertion the caller cannot see is a deletion. Both
 * failures are the safe direction.
 */
function project(content) {
  const withoutComments = content.split('');
  const codeOnly = content.split('');
  let state = 'code';

  const blankBoth = (at) => {
    withoutComments[at] = ' ';
    codeOnly[at] = ' ';
  };

  for (let i = 0; i < content.length; i += 1) {
    const here = content[i];
    const next = content[i + 1];

    if (state === 'code') {
      if (here === '/' && next === '/') {
        state = 'line';
        blankBoth(i);
      } else if (here === '/' && next === '*') {
        state = 'block';
        blankBoth(i);
        // Both characters of the opener are consumed, or `/*/` would read as an opener whose `*` also
        // begins the closer — one keystroke that ends a comment the compiler considers still open, and
        // with it every claim this projection makes about what is code.
        blankBoth(i + 1);
        i += 1;
      } else if (here === '"' && next === '"' && content[i + 2] === '"') {
        state = 'text';
        i += 2;
      } else if (here === '"') {
        state = 'string';
      } else if (here === "'") {
        state = 'char';
      }
      continue;
    }

    if (state === 'line') {
      if (here === '\n') state = 'code';
      else blankBoth(i);
      continue;
    }

    if (state === 'block') {
      blankBoth(i);
      if (here === '*' && next === '/') {
        blankBoth(i + 1);
        i += 1;
        state = 'code';
      }
      continue;
    }

    // Inside a literal: its CONTENTS are not code, but they are still text that the timeout and
    // ticket patterns read — `"30s"`, `@Disabled("ALFA-1234 …")` — so only the stricter view loses them.
    if (here === '\\' && (state === 'string' || state === 'char')) {
      codeOnly[i] = ' ';
      if (i + 1 < content.length) codeOnly[i + 1] = ' ';
      i += 1;
      continue;
    }
    if (state === 'text' && here === '"' && next === '"' && content[i + 2] === '"') {
      i += 2;
      state = 'code';
      continue;
    }
    if ((state === 'string' && here === '"') || (state === 'char' && here === "'")) {
      state = 'code';
      continue;
    }
    codeOnly[i] = ' ';
  }

  return { withoutComments: withoutComments.join(''), codeOnly: codeOnly.join('') };
}

// One file is projected several times per scan — once per unpermitted statement in `scan.mjs`, twice
// per version in `conceal.mjs` — and the pass is over the whole content. Without this the scan of a
// file with many statements is quadratic, and a pre-write hook that overruns its 15-second budget is
// a check that did not run.
//
// Two slots rather than one because the interesting callers hold two versions at once: `conceal.mjs`
// alternates between the file as it was and as it will be, and a single slot would recompute on every
// alternation — the cache would cost a branch and buy nothing.
const cache = new Map();

function projectionOf(content) {
  const hit = cache.get(content);
  if (hit !== undefined) return hit;
  const projection = project(content);
  if (cache.size >= 2) cache.delete(cache.keys().next().value);
  cache.set(content, projection);
  return projection;
}

/** The file with comments blanked. String and character literals are left intact. */
export function withoutComments(content) {
  return projectionOf(content).withoutComments;
}

/** The file with comments AND the contents of string, character and text-block literals blanked. */
export function codeOnly(content) {
  return projectionOf(content).codeOnly;
}

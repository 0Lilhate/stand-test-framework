// The write the scanner never saw: a file created by the shell.
//
// `pre-write` is the whole enforcement layer for content — it scans what a tool is about to leave
// behind, refuses a blocking finding while the line is being typed, and records the artifact so the
// safety gate can hold it at the end of the session. It is wired to Write, Edit and MultiEdit,
// because those are the tools whose payload IS the content. `cat > Test.java` carries the same
// content past all of it: nothing scans it, nothing records it, and the Stop gate then ends the
// session reporting that there is nothing to review — the most expensive shape of clean report there
// is, because it is indistinguishable from having done the work.
//
// So this file draws the perimeter around the ROUTE rather than the content. Not "is this content
// safe" — that question already has an answer, and the answer runs in `scan.mjs` — but "did this
// content take the road where that question gets asked". A shell write into the working tree is
// refused and the sanctioned tool is named in the refusal.
//
// **What is deliberately still open.** This is a perimeter against the convenient shortcut, not
// against a determined bypass, and pretending otherwise would be the same overstatement the permit
// note in `permit.mjs` refuses to make. `python3 -c` with the write spelled some way this file does
// not recognise, a script written to /tmp and executed, a Gradle task that writes sources — each
// gets through, and each takes more deliberation than the honest mistake this closes. What the
// perimeter buys is that the fast path is the checked path: writing a test the ordinary way is the
// only way that is not refused.

import { basename, isAbsolute, relative, resolve, sep } from 'node:path';
import { STATE_DIR } from './bundle.mjs';

/** Words that stand in front of the real command without changing what it does. */
const PREFIXES = new Set(['sudo', 'env', 'nohup', 'time', 'command', 'nice', 'ionice', 'xargs', 'exec', 'stdbuf', 'setsid']);

/** Nested shells: the script is a command line of its own, and is read as one. */
const SHELLS = new Set(['sh', 'bash', 'zsh', 'dash', 'ksh']);

/** Interpreters that write files when told to inline. */
const INTERPRETERS = new Set(['python', 'python2', 'python3', 'node', 'ruby', 'perl', 'php', 'deno', 'bun']);

/** What an inline script looks like when it writes a file. Recognises the common spellings, not all. */
const INLINE_WRITE = [/\bwriteFile(?:Sync)?\s*\(/, /\.write(?:lines|_text|Text)?\s*\(/i, /\bopen\s*\([^)]*['"][waxWAX]\+?['"]/, /\bcreateWriteStream\s*\(/, /\bFile\.(?:write|open)\b/, /\bcopyfile|shutil\.(?:copy|move)/];

/** In-place editors: the file goes in and comes out changed, and no tool in between is scanned. */
const IN_PLACE = {
  sed: /^-(?:[a-zA-Z]*i|-in-place)/,
  perl: /^-(?:[a-zA-Z]*i|-in-place)/,
  ruby: /^-(?:[a-zA-Z]*i|-in-place)/,
  awk: /^-(?:i|-in-place)/,
  gawk: /^-(?:i|-in-place)/,
};

/** Commands whose last positional argument is the file they land on. */
const LANDS_ON_LAST = new Set(['cp', 'mv', 'install', 'rsync', 'ln', 'truncate']);

/**
 * Commands that change files the command line does not name.
 *
 * A patch carries its own targets inside the diff, and `wget` derives a name from the URL. Neither
 * can be resolved from here, and "cannot be resolved" has to fail closed: the alternative is a rule
 * that waves through precisely the writes it cannot see.
 */
const OPAQUE_TARGET = new Set(['patch', 'wget']);

/** Directories whose contents are output rather than artifacts — nothing there is reviewed, ever. */
const OUTPUT_DIRECTORIES = new Set(['build', 'out', 'target', 'node_modules', '.gradle', '.git', '.idea']);

/** The hooks' own bookkeeping, which the session writes by design. */
const HOOK_STATE = STATE_DIR.split('/').join(sep);

const DEVICES = /^\/dev\/(?:null|stdout|stderr|tty|fd\/\d+)$/;

/** A word carrying an expansion cannot be resolved to a path, and is not guessed at. */
const UNRESOLVABLE = /[$`*?~]|\$\(/;

/**
 * One command line, split into simple commands.
 *
 * Character by character rather than by regular expression, because every interesting case turns on
 * quoting: `grep "a > b"` is not a redirect, `2>&1` is not a file, and `git log --format='%h -> %s'`
 * is neither. A regex sees three writes there and a session that refuses ordinary work is a session
 * whose perimeter gets switched off within the hour.
 *
 * @returns `[{ words: [...], targets: [...] }]` — targets are the files redirected INTO.
 */
export function parse(command) {
  const commands = [];
  let words = [];
  let targets = [];
  let current = null;
  let expect = null;  // 'target' after `>`, 'discard' after `<`
  const heredocs = [];
  let index = 0;

  const push = () => {
    if (current === null) return;
    if (expect === 'target') targets.push(current);
    else if (expect !== 'discard') words.push(current);
    expect = null;
    current = null;
  };
  const finish = () => {
    push();
    if (words.length > 0 || targets.length > 0) commands.push({ words, targets });
    words = [];
    targets = [];
  };
  const add = (text) => {
    current = (current === null ? '' : current) + text;
  };

  while (index < command.length) {
    const char = command[index];

    if (char === '\\' && index + 1 < command.length) {
      add(command[index + 1]);
      index += 2;
      continue;
    }

    if (char === "'" || char === '"') {
      const close = closingQuote(command, index);
      add(command.slice(index + 1, close));
      // An empty quoted word is still a word — `sed -i '' 's/a/b/' f` depends on it surviving.
      if (current === null) current = '';
      index = close + 1;
      continue;
    }

    if (char === ' ' || char === '\t') {
      push();
      index += 1;
      continue;
    }

    if (char === '\n' || char === ';') {
      finish();
      index += 1;
      if (char === '\n' && heredocs.length > 0) {
        const bodies = readHeredocBodies(command, index, heredocs);
        index = bodies.end;
        // The body belongs to the command that opened it — `python3 - <<PY` is an inline script by
        // another spelling, and reading it as one is the difference between closing the route and
        // closing the one way of taking it that happens to be shortest to type.
        if (commands.length > 0) commands[commands.length - 1].heredoc = bodies.text;
      }
      continue;
    }

    if (char === '|' || char === ')' || char === '(') {
      finish();
      index += command[index + 1] === '|' ? 2 : 1;
      continue;
    }

    if (char === '&') {
      // `&>file` redirects both streams; `>&2` and `2>&1` duplicate a descriptor and touch no file.
      if (command[index + 1] === '>') {
        push();
        index += 1;
        continue;
      }
      if (expect === 'target') {
        expect = null;
        index += 1;
        while (index < command.length && !' \t\n;|&'.includes(command[index])) index += 1;
        continue;
      }
      finish();
      index += command[index + 1] === '&' ? 2 : 1;
      continue;
    }

    if (char === '>') {
      // A leading file descriptor belongs to the operator, not to the previous word.
      if (current !== null && /^\d+$/.test(current)) current = null;
      push();
      index += 1;
      if (command[index] === '>' || command[index] === '|') index += 1;
      expect = 'target';
      continue;
    }

    if (char === '<') {
      push();
      index += 1;
      if (command[index] === '<' && command[index + 1] !== '<') {
        index += 1;
        if (command[index] === '-') index += 1;
        const delimiter = readWord(command, index);
        heredocs.push(delimiter.value);
        index = delimiter.end;
        continue;
      }
      if (command[index] === '<') index += 1;
      expect = 'discard';
      continue;
    }

    add(char);
    index += 1;
  }
  finish();
  return commands;
}

/** The index of the quote that closes the one at `open`. Unterminated quotes run to the end. */
function closingQuote(text, open) {
  const quote = text[open];
  for (let index = open + 1; index < text.length; index += 1) {
    if (quote === '"' && text[index] === '\\') {
      index += 1;
      continue;
    }
    if (text[index] === quote) return index;
  }
  return text.length;
}

/** The bare word starting at `index`, quotes stripped. */
function readWord(text, index) {
  let start = index;
  while (start < text.length && (text[start] === ' ' || text[start] === '\t')) start += 1;
  let value = '';
  let cursor = start;
  while (cursor < text.length && !' \t\n;|&<>()'.includes(text[cursor])) {
    if (text[cursor] === "'" || text[cursor] === '"') {
      const close = closingQuote(text, cursor);
      value += text.slice(cursor + 1, close);
      cursor = close + 1;
      continue;
    }
    value += text[cursor];
    cursor += 1;
  }
  return { value, end: cursor };
}

/**
 * The bodies of the heredocs opened on the line just ended, and where they stop.
 *
 * Taken out whole rather than parsed as shell: a body is data, and reading `it's fine` as an
 * unterminated quote would swallow the rest of the command — including the second redirect, which is
 * the one that matters.
 */
function readHeredocBodies(command, index, heredocs) {
  let cursor = index;
  const collected = [];
  while (heredocs.length > 0) {
    const delimiter = heredocs.shift();
    for (;;) {
      const newline = command.indexOf('\n', cursor);
      const line = command.slice(cursor, newline === -1 ? command.length : newline);
      cursor = newline === -1 ? command.length : newline + 1;
      if (line.trim() === delimiter || newline === -1) break;
      collected.push(line);
    }
  }
  return { end: cursor, text: collected.join('\n') };
}

/** The command being run, with the words that merely stand in front of it removed. */
function head(words) {
  let index = 0;
  while (index < words.length) {
    const word = words[index];
    // `env FOO=bar cmd` and `FOO=bar cmd` both put an assignment where the name goes.
    if (/^[A-Za-z_][A-Za-z0-9_]*=/.test(word) || PREFIXES.has(basename(word))) {
      index += 1;
      continue;
    }
    return { name: basename(word), arguments: words.slice(index + 1) };
  }
  return { name: '', arguments: [] };
}

function positional(words) {
  return words.filter((word) => !word.startsWith('-'));
}

/** The value of `-o`/`--output`-style flags, in either spelling. */
function flagValue(words, short, long) {
  for (let index = 0; index < words.length; index += 1) {
    if (words[index] === short || words[index] === long) return words[index + 1];
    if (words[index].startsWith(`${long}=`)) return words[index].slice(long.length + 1);
    if (short.length === 2 && words[index].startsWith(short) && words[index].length > 2) return words[index].slice(2);
  }
  return undefined;
}

/** Every file one simple command would create or change, target unknown where it cannot be known. */
function writesOf(simple) {
  const found = simple.targets.map((target) => ({ target, how: 'перенаправление вывода' }));
  const { name, arguments: rest } = head(simple.words);

  if (SHELLS.has(name)) {
    const script = flagValue(rest, '-c', '--command');
    if (script !== undefined) {
      // The nested script is a command line, and gets exactly the same reading.
      for (const nested of parse(script)) found.push(...writesOf(nested));
    }
  }

  if (name === 'tee') {
    for (const file of positional(rest)) found.push({ target: file, how: 'tee' });
    if (positional(rest).length === 0) found.push({ target: null, how: 'tee' });
  }

  const inPlace = IN_PLACE[name];
  if (inPlace !== undefined && rest.some((word) => inPlace.test(word))) {
    const files = positional(rest);
    found.push({ target: files.length > 0 ? files[files.length - 1] : null, how: `${name} -i (правка на месте)` });
  }

  if (LANDS_ON_LAST.has(name)) {
    const files = positional(rest);
    found.push({ target: files.length > 0 ? files[files.length - 1] : null, how: name });
  }

  if (name === 'dd') {
    const output = rest.find((word) => word.startsWith('of='));
    found.push({ target: output === undefined ? null : output.slice(3), how: 'dd' });
  }

  if (name === 'curl') {
    const output = flagValue(rest, '-o', '--output');
    if (output !== undefined) found.push({ target: output, how: 'curl -o' });
    if (rest.includes('-O') || rest.includes('--remote-name')) found.push({ target: null, how: 'curl -O' });
  }

  if (OPAQUE_TARGET.has(name)) {
    found.push({ target: flagValue(rest, '-O', '--output-document'), how: name });
  }

  if (name === 'git' && rest[0] === 'apply') {
    found.push({ target: null, how: 'git apply' });
  }

  if (INTERPRETERS.has(name)) {
    const inline = flagValue(rest, '-c', '--command') ?? flagValue(rest, '-e', '--eval');
    for (const [script, how] of [[inline, `${name} -c/-e`], [simple.heredoc, `${name} <<HEREDOC`]]) {
      if (script !== undefined && script !== null && INLINE_WRITE.some((pattern) => pattern.test(script))) {
        found.push({ target: null, how });
      }
    }
  }

  // An archive carries its own paths, and unpacking one lands every file it holds in the tree. `-C`
  // says where, and nowhere says what.
  // `x` among the short flags, or the long spelling. Matched against whole flag words only, so
  // `--exclude=x` and a file called `x.tar` are not read as an extraction.
  const extracts = (word) => word === '--extract' || (/^-?[a-zA-Z]+$/.test(word) && word.includes('x'));
  if (name === 'tar' && rest.some(extracts)) {
    found.push({ target: flagValue(rest, '-C', '--directory') ?? '.', how: 'tar -x' });
  }
  if (name === 'unzip' && !rest.some((word) => ['-l', '-t', '-v', '-p', '-z'].includes(word))) {
    found.push({ target: flagValue(rest, '-d', '--directory') ?? '.', how: 'unzip' });
  }

  return found;
}

/**
 * Every subcommand of this guard that a command line would invoke.
 *
 * The guard's own bookkeeping is the thing its gates read, and several of its subcommands exist for
 * the HOST to call — `subagent-stop` writes the evidence that a separate context ran, `post-run`
 * writes the run journal, `stop` decides whether a session may end. Called by hand they do not report
 * a fact, they manufacture one: a run that types `stand-guard.mjs subagent-stop` has certified its own
 * review, and every gate downstream reads that certificate as if a subagent had produced it.
 *
 * Read through the same parser as everything else here, so `sh -c`, a leading `env`, quoting and an
 * absolute path to the script are all one case rather than four spellings to enumerate in a permission
 * rule. The allow-list still narrows the entry point; this is what makes the narrowing hold when the
 * spelling changes.
 *
 * @returns the subcommand words, in the order they appear; empty when the guard is not invoked
 */
export function guardSubcommands(command) {
  const found = [];
  const collect = (simple) => {
    const { name, arguments: rest } = head(simple.words);
    if (SHELLS.has(name)) {
      const script = flagValue(rest, '-c', '--command');
      if (script !== undefined) parse(script).forEach(collect);
      return;
    }
    // Either `node …/stand-guard.mjs <sub>` or the script executed directly through its shebang.
    const isGuard = (word) => /(^|\/)stand-guard\.mjs$/.test(word);
    const words = isGuard(name) ? rest : rest.slice(rest.findIndex(isGuard) + 1);
    if (!isGuard(name) && !rest.some(isGuard)) return;
    const sub = words.find((word) => !word.startsWith('-'));
    if (sub !== undefined) found.push(sub);
  };
  parse(command).forEach(collect);
  return found;
}

/**
 * Where a target lands, from the working tree's point of view.
 *
 * The working tree is asked FIRST, before any temporary-directory rule. A project checked out under
 * `/var/folders` — which is where a macOS temporary directory lives, and where every test of this
 * file runs — is still a project, and its sources are still artifacts.
 */
function placeOf(target, cwd) {
  if (target === null || target === undefined || target === '') return 'unresolved';
  if (UNRESOLVABLE.test(target)) return 'unresolved';
  if (DEVICES.test(target)) return 'device';

  const absolute = isAbsolute(target) ? resolve(target) : resolve(cwd, target);
  const inside = relative(resolve(cwd), absolute);
  if (inside !== '' && !inside.startsWith('..') && !isAbsolute(inside)) {
    if (inside === HOOK_STATE || inside.startsWith(`${HOOK_STATE}${sep}`)) return 'output';
    return inside.split(sep).some((segment) => OUTPUT_DIRECTORIES.has(segment)) ? 'output' : 'workspace';
  }

  // The POSIX spellings only, and deliberately NOT `os.tmpdir()`. On macOS that resolves to a
  // per-user directory under /var/folders, and a project checked out inside it — which is where every
  // test of this file puts one — would make its own SIBLING directories count as scratch space. The
  // rule has to hold for the awkward layout too, or it holds only where it was tried.
  const temporary = ['/tmp', '/private/tmp', '/var/tmp'];
  const isTemporary = temporary.some((root) => absolute.startsWith(`${root}${sep}`));
  return isTemporary ? 'temporary' : 'outside';
}

/** Why a place is refused, in the words the refusal uses. */
const REFUSAL = {
  workspace: 'в рабочем дереве — содержимое не увидит ни скан, ни реестр артефактов',
  outside: 'за пределами рабочего дерева — за то, чего он не видит, гард не отвечает',
  unresolved: 'путь не разрешается статически (переменная, подстановка или команда сама выбирает имя)',
};

/**
 * Every write a command would perform that this guard could not otherwise see.
 *
 * @param command the command line as the tool call carries it
 * @param cwd the workspace root
 * @returns `[{ target, how, place, why }]`; empty when the command writes nothing that matters
 */
export function fileWrites(command, cwd) {
  const found = [];
  const seen = new Set();
  for (const simple of parse(command)) {
    for (const write of writesOf(simple)) {
      const place = placeOf(write.target, cwd);
      if (place === 'device' || place === 'output' || place === 'temporary') continue;
      const key = `${write.how} ${write.target}`;
      if (seen.has(key)) continue;
      seen.add(key);
      found.push({ ...write, place, why: REFUSAL[place] });
    }
  }
  return found;
}

/** The refusal as a person reads it: what was seen, why it is refused, and the road that is open. */
export function renderWrites(writes) {
  const lines = writes.map((write) => `  ${write.target === null ? '<цель не названа в команде>' : write.target} (${write.how}): ${write.why}`);
  return `✖ команда отвергнута: запись файла в обход проверок (${writes.length})\n${lines.join('\n')}\n\n`
    + 'Файлы пишут Write/Edit/MultiEdit — только на них стоит pre-write: скан по detectors.json, сверка с прежней\n'
    + 'версией (удалённая ассерция, выключённый тест, выросший таймаут) и запись артефакта в реестр, без которой\n'
    + 'safety-review в конце сессии не о чем докладывать. Шелл-запись обходит всё три, и зелёный отчёт в конце\n'
    + 'означал бы только то, что проверять было нечего.\n'
    + 'Временный файл — в /tmp; вывод сборки — в build/. Если запись действительно нужна вне этого — сделайте её сами, вне агента.';
}

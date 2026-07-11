# application.yml / stand-test-environments.yml generation checklist

Binary items; any FAIL blocks the apply. Run AFTER the merge, over the resulting file.

## Structure

- [ ] The tree matches the SDK schema exactly (`environments.<env>...` or
      `stand.test.environments.<env>...`); kebab-case registry keys; no invented keys.
- [ ] Every alias bound by the KB for this environment is present; map keys are the entities'
      KB `alias` values verbatim (for services — the service `id`, which is its registry alias).
- [ ] Keys outside the managed subtree are byte-identical to the pre-merge file.
- [ ] Deterministic order inside the subtree (services, topics, datasources, grpc-targets,
      kafka-cluster, kafka-clusters; aliases sorted).

## References and secrets

- [ ] Every `*-ref` is an env-var NAME — never a URL, JDBC string or credential value. The
      `${NAME}`/`${NAME:default}` spelling in refs is allowed ONLY on the plain-JUnit file surface
      (`stand-test-environments.yml`, where the SDK resolves it lazily); on the starter surface
      Spring would resolve `${...}` inside a `*-ref` into a VALUE at startup — refs there are
      bare NAMES.
- [ ] No inline default on any credential ref (`password-ref`, `token-ref`, `user-ref`).
- [ ] Starter surface only: value twins only for `base-url`/`url`/`target`/`bootstrap-servers`/
      `security-protocol`, each a `${ENV_VAR:}` placeholder with EMPTY default, and no field sets
      both the twin and the `-ref` form.
- [ ] Plain-JUnit surface: no value twins at all (refs-only file).
- [ ] Real topic names appear only in `topics.<alias>.name`.

## Behavior

- [ ] File re-parses as YAML; the registry loader/starter binding accepts it where runnable.
- [ ] The report lists every env var required at run time (refs + placeholder names).
- [ ] No production environment declared.
- [ ] The diff was shown before apply; dry-run wrote nothing.

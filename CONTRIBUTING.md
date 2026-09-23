# Contributing

This repository is a language-neutral scaffold. Keep implementation and
toolchain decisions inside the module that owns them, and do not add language
source or generated artifacts to the root policy layer.

## Commits

Use Git-std Conventional Commits with one of the explicit scopes configured in
`.git-std.toml`. For example:

```text
docs(repo): clarify the scaffold boundary
```

Run commit checks through the root Just interface. Do not add a new scope by
relying on discovery from a language workspace.

## Formatting

Use Prim for connective-tissue formatting. Run `just fmt` when formatting files
and use `just fmt-check` to verify formatting without writing changes.

## Verification

Before submitting a change, run `just verify`. This runs commit linting and the
repository build gate, including formatting, Prim linting, and scaffold checks.
All root commands are exposed through Just so the command policy stays
centralized.

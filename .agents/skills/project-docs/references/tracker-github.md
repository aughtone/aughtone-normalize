# Tracker Binding: GitHub (docs sync)

**Page names repeat the record id, and shortening them is opt-in.** GitHub renders the page name - derived from the file path - as the page title, above the document's own H1, so `RAD-0001-…` publishes as "Research RAD 0001 …" over "# The actual title". Setting `"wikiShortPageNames": true` in `.agents/config/story-tools.json` drops the id from the page name.

It is off by default because turning it on **renames every affected page**: inbound links to the old names break, and the old pages need `--allow-delete` to prune. On a wiki that already exists that is a migration, not a fix. Preview it with `--short-page-names --dry-run`, which lists every rename and changes nothing. A wiki being created for the first time can turn it on before the first sync and pay nothing.

**Who can read this wiki is the repo's visibility, not the tracker's.** On a public repo the wiki is world-readable the moment it syncs - no review, no approval, and no way back: deleting a page later does not unpublish it, and anything cloned or cached in between stays gone. On a private repo it is behind auth, like a YouTrack KB. The sync prints which one you are in on every run; read that line before filing operational detail.

This is not a prohibition. A runbook that names its CI secret *variables*, or explains that org secrets do not show up in `gh secret list -R`, is exactly the content that saves the next reader a wasted session - and names in a public workflow file were never secret. Weigh what is genuinely new disclosure: an org's plan tier, an internal hostname, a path layout. The test is not "does this mention a secret" but "does publishing this tell someone something they could not already see".

The knowledge base is the repo's **wiki** - itself a git repo
(`<repo>.wiki.git`), so sync is git plumbing with the same three-way
merge model as YouTrack. Capability-detected: no wiki (disabled, or a
private repo without a paid plan) → `docs/knowledge/` stays git-native
with no mirror, and everything else in this skill still applies.

Sync command (script bundled in this skill):

```
scripts/gh-wiki-sync.sh [KB_DIR] [--repo owner/repo]
                        [--pull-only] [--allow-delete] [--force] [--dry-run]
```

- Domain: `KB_DIR` (default `./docs/knowledge`) ⇄ the repo wiki.
- **Structure flows UP** - the opposite of YouTrack. The wiki has no
  hierarchy UI, so the local tree owns the layout: page names encode the
  path (`architecture/decisions/foo.md` → `Architecture-Decisions-Foo`),
  a section's `README.md` is the section page, `KB_DIR/README.md` is
  Home, and a generated `_Sidebar.md` shows the tree (never edit it in
  the wiki - it is overwritten). Reorganize by moving files locally; an
  unchanged moved file becomes a page rename, a move+edit degrades to
  delete+create (run with `--allow-delete` after a reorganize to prune).
- **Content flows both ways.** Wiki UI edits pull; local edits push;
  both at once three-way merge against the base recorded in
  `KB_DIR/.gh-wiki-sync/` (commit it; never hand-edit). Conflicts get
  git markers, are NEVER pushed until resolved. Exit codes: 0 ok,
  1 error, 2 conflicts to resolve.
- A page created fresh in the wiki UI lands at the KB root on pull,
  reported under "New from wiki" - file it into a section (the next
  sync renames the page to match).
- Deletes: a wiki-side delete prunes an unedited local file, conflicts
  an edited one. A local delete is report-only until `--allow-delete`.
- Bootstrap: empty `KB_DIR` pulls the whole wiki. Non-empty without
  sync state refuses unless `--force`, which adopts every local file as
  a page (local wins on same-named pages) - the legacy-adoption path.
- Setup requirements: the wiki must be **enabled** on the repo and
  **initialized** (GitHub only creates `<repo>.wiki.git` after the
  first page is saved in the web UI - create Home once). The script
  reports each case distinctly.
- Repo resolves from `--repo` or `tracker.repo` in
  `.agents/config/story-tools.json`; token from the story-tools
  connections (`$GITHUB_TOKEN` → pointer connection env → legacy
  `github.env` → `gh auth token`) with the Contents read/write
  permission. Never ask for or accept tokens in conversation - if
  authentication fails, point the user at `.agents/setup.sh` (or the
  installer, on older binds).
- Requires `git` and `python3` on PATH.
- **Links are rewritten, both ways.** Write ordinary repo-relative links
  (`[Scope](../requirements/PRD-0002-scope.md)`); push converts them to the
  wiki's flat page names and pull converts them back, so the working tree
  is the only place paths exist. A target that is not a synced document -
  a directory, a source file, anything outside `docs/knowledge/` - becomes
  a repo link instead. A link that resolves to nothing is left untouched
  and reported, so a typo stays visibly broken rather than becoming a
  plausible URL that 404s.
- **Images.** The wiki has no attachment store, so an embed is served from
  the repo: keep the image beside the document, embed it by relative path
  (`![Flow](checkout-flow.png)`), and push rewrites it to a `?raw=1` repo
  URL that carries the reader's session - so it renders on a private repo
  too. Nothing is uploaded and nothing needs a new name to change: replace
  the file in the repo and the wiki follows.

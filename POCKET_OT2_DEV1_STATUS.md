# Pocket OT2 v0.4.9-dev1 status

Branch: `agent/pocket-ot2-v049`

Canonical backlog: issue #7.

## Local source snapshot

`PocketOT-v0.4.9-dev1-source.zip`

SHA-256: `13cc63e1592e5effa647c6788c357aedb7a19ec1cc8c9574ce1080b8fbf19f5a`

The source snapshot was generated from the v0.4.8.2 backup-compat baseline and includes the first v0.4.9 interaction/correctness pass.

## Implemented in dev1

- Pocket OT / Ergo de Poche user-facing branding cleanup.
- Approved launcher artwork used for in-app branding.
- Configuration/decrement correction noise filtered from reports; decrement/undo does not create a second administrative activity event.
- Empty Timeline hours can open activity entry with the selected time prefilled.
- Today routines can be reordered with accessible up/down controls.
- Water/Coffee remain normal-size routine rows with trailing minus/count/plus controls.
- Regular Done can be undone by removing its completion event rather than adding an undo event.
- Existing Timeline events can edit date/time, category/context, title/notes, and receive photos after creation.
- Android keyboard overlap fix: `adjustResize`, VisualViewport handling, focused-field scrolling, bottom navigation hidden while IME is open.
- Context Pictures shortcut opens the photo/context capture view rather than reports.
- Print-safe report margin set to 8 mm.

## Still open for the next pass

- Conditional follow-up/sub-reminder engine.
- EXIF photo timestamp chooser (Photo time / Now / custom).
- Swipeable reminder carousel.
- Remaining v0.4.9 polish and device validation.

## Validation completed

- `node --check app/src/main/assets/www/app.js`
- Android manifest and string XML parse checks.

## Publication note

The connected GitHub app can manage branches/issues/text repository objects, but this execution environment does not expose an authenticated `gh` CLI and the connector does not accept local binary/archive paths. The complete dev1 source snapshot therefore remains packaged separately until a source upload path is available; this file records its exact checksum so the branch state is auditable.

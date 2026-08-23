# windows-outlook-teams

description: Where Outlook and Teams keep data on Windows, how to reach it, and what lies to you.
triggers: outlook, teams, calendar, meetings, mailbox, inbox, mail, appointments, com, mapi, ost, pst, olk, msteams, meeting, invite, sent items, аутлук, тиймс, календар, срещи, среща, поща, имейл, имейли, писма, кутия, пощенска, leveldb, indexeddb, ebwebview

This is a map, not a recipe. It says where things are, how to open them, and which answers are
lies. What to do with what you find is the user's call — ask them rather than deciding a shape
for the output.

## Read this before doing anything else

**Classic Outlook is reachable only through COM, and COM is reachable only through
`run_command`.** If `run_command` is unavailable — a read-only session, or a denied tool — then
there is no route to a mailbox at all, and the only correct answer is to say so and stop. Say
which flags would change it: `--approval ask` or `--approval auto`, with `--shell-scope
unrestricted`.

**There is nothing to find on the filesystem, so do not go looking.** Outlook does not leave
`.eml` or `.msg` files anywhere. The `.ost` and `.pst` files are MAPI databases, exclusively
locked while Outlook runs and unreadable as text when it does not. A search of `AppData` costs
tens of seconds and answers nothing. One run of `read_leveldb` is the only file-level route here,
and it reads Teams, not Outlook.

**Never name a store the user's "main account" unless they said so.** A profile can carry a
stale entry pointing at a deleted file; that is a leftover, not evidence of where their mail
lives. Report what each store is and what it held, and let them tell you which one matters.

## Verified against

Everything below was established by running it, on 2026-08-22, against:

| | |
|---|---|
| Windows | 11 Pro, build 22621, amd64 |
| classic Outlook | Office 16.0.20326.20100 (Click-to-Run), `Office16\OUTLOOK.EXE` |
| new Outlook | Microsoft.OutlookForWindows 1.2026.728.0 |
| Teams | MSTeams 26198.304.4946.9672 |
| Java | 25.0.4, JLine's native Windows terminal |

Re-check rather than trust when the versions differ. The paragraph below says which parts are
likely to have moved.

## What a newer version is likely to break

**The Teams store, first and worst.** Its IndexedDB layout is internal and has already changed
once: parsers written for Teams 1.x return nothing at all on the current client, silently. If a
scan finds no meetings, that is as likely to mean the schema moved as that the calendar is empty
— check that the file holds a subject you know before believing a zero.

**The Teams profile folder is per account type.** `WV2Profile_tfl` is the personal one — "Teams
for Life". A work or school account writes under a different profile directory in the same
`EBWebView` folder. List what is there instead of assuming this name.

**Classic Outlook has a horizon.** Microsoft's direction is the new client, and COM is not coming
to it. The object model is stable and decades old, so the calls here will keep working for as
long as classic Outlook is installed — but on a machine that only ever had the new one, none of
the COM half applies and the answer is Graph or nothing.

**Paths that carry a version.** The registry CLSID is fixed, but `Office16` in the executable
path is the Office major version and would become `Office17`. Read `LocalServer32` rather than
building the path.

Stable enough to rely on: the folder ids, `Restrict` and its date format, `ConversationTopic`,
`IncludeRecurrences`, and the four traps below — those are properties of MAPI, not of a build.

---

## First: which Outlook is on this machine

Windows 11 ships two programs called Outlook and only one can be automated. Establish which
before promising anything.

```powershell
(Get-ItemProperty "HKLM:\SOFTWARE\Classes\CLSID\{0006F03A-0000-0000-C000-000000000046}\LocalServer32" -EA SilentlyContinue).'(default)'
Get-Process olk, OUTLOOK -EA SilentlyContinue | Select-Object ProcessName, Path
Get-AppxPackage MSTeams, Microsoft.OutlookForWindows -EA SilentlyContinue | Select Name, Version
```

| | classic `OUTLOOK.EXE` | new `olk.exe` |
|---|---|---|
| COM / MAPI | **yes** | **no** — Microsoft states this outright |
| local store | `.ost` / `.pst` | a WebView2 IndexedDB, undocumented |
| what is reachable | everything the object model exposes | little, and only by reverse engineering |

**Adding an account through the default Windows 11 experience puts it in the new one.** The two
keep separate profiles and do not share. A machine can have classic installed, registered, and
answering COM, while every mailbox lives in the other program — the object model then opens
onto an empty profile. Check that a store exists before concluding anything about a mailbox.

---

## Classic Outlook, through COM

```powershell
$ol = New-Object -ComObject Outlook.Application
$ns = $ol.GetNamespace("MAPI")
```

Folder ids worth knowing: `3` Deleted Items, `5` Sent Items, `6` Inbox, `9` Calendar, `10`
Contacts. `Items.Restrict("[Start] >= 'MM/dd/yyyy HH:mm' AND ...")` filters server-side and is far
cheaper than walking. `$items.IncludeRecurrences = $true` expands a recurring series into
occurrences — necessary for "when was I busy", wrong for "how many distinct meetings".
`ConversationTopic` groups a thread; `Store`, `SenderName`, `ReceivedTime`, `SentOn`, `Duration`
and `BodyPreview` are the fields most questions need.

**A conversation is not a subject and not a topic of work.** Grouping on the literal `Subject`
splits every thread in two, because the first message has no `RE:` — use `ConversationTopic`,
which is the subject with the prefixes already stripped. And a question about clients, projects
or workstreams is a level above that again: one engagement runs across many conversations, so a
top five of conversations answers a question nobody asked. `email-triage` says what does carry
the grouping.

`ConversationTopic` can also mislead in the other direction: it is matched on text, so two
unrelated threads both called "Status" merge into one. Where a grouping looks wrong, say so
rather than reporting it.

### Four things that will waste your time

**`RPC_E_CALL_REJECTED` means two different things.** Outlook returns it while it is busy, and
also while it is showing a modal dialog. The first clears itself; the second never will, and no
amount of retrying helps. Tell them apart by looking for a window of class `#32770` belonging to
the process — that is a dialog, and it needs a person. Retry a few times with a growing wait, then
say which one it probably is instead of hanging.

**`Namespace.GetDefaultFolder` follows the profile's *default* store**, which need not be a
mailbox and need not open. A stale "Outlook Data File" entry whose `.pst` has been deleted makes
every folder call answer "the file cannot be found" while a perfectly good account sits in the
next slot — and raises that dialog on every launch. Walk `$ns.Stores` by index and use
`$store.GetDefaultFolder(n)`, skipping stores that refuse to open. Enumerating `$ns.Stores`
through the pipeline can itself throw; index it with `.Item($i)`.

**And `$store.GetDefaultFolder(6)` lies on every store that is not the delivery store.** A `.pst`
added as a data file — an archive, an export, someone else's mailbox — has no default Inbox, and
the call does not say so: it returns a folder with an **empty name and no items**, so a store
holding a full year of mail reports zero and looks exactly like an empty one. `GetDefaultFolder(5)`
for Sent Items throws outright on the same store, while `GetDefaultFolder(9)` for the calendar
works. Measured on 2026-08-23:

```
store 'Outlook Data File'
   GetDefaultFolder(6) -> ''  items=0        <- 111 messages are sitting in a folder named Inbox
   GetDefaultFolder(5) FAILED :: An object could not be found
   GetDefaultFolder(9) -> 'Calendar' items=15
   plain folders: Deleted Items(0), Calendar(15), Inbox(111), Sent Items(45)
```

So: **check the name of the folder you were handed.** If it is empty, or the count is zero, walk
`GetRootFolder().Folders` instead and read the folders by name. Zero from a store that opened
cleanly is not evidence of an empty mailbox — it is usually evidence of asking the wrong way.

**An account that has not synced answers zero.** Zero meetings and no mailbox look identical in
any summary, and only one of them is an answer. When no store opened, say so; never report a
count you did not really obtain.

**A Gmail/IMAP account in classic Outlook gets a calendar marked "This computer only".** It is
local, and appointments made on the web are not in it.

### What cannot be done, so do not try

- **`ReceivedTime` cannot be set.** `PropertyAccessor` accepts `PR_MESSAGE_DELIVERY_TIME`
  (`0x0E060040`) without complaint and the value does not change. Mail cannot be back-dated, so
  test data with a spread of dates cannot be built this way.
- **A mail created with `Items.Add` lands in Drafts, not in the folder it was created on.**
  `inbox.Items.Add(0)` then `Save()` succeeds, `inbox.Items.Count` stays where it was, and the
  item is sitting in Drafts — which reads exactly like "the write was discarded" and is not.
  Verified on 2026-08-23: six items created against a Gmail inbox were all in Drafts. `Save()`
  saves an unsent message, and an unsent message belongs in Drafts. To place one somewhere else,
  `Move()` it after saving, or import a `.eml` with `Namespace.OpenSharedItem`.
- **Drafts are not received mail, and counting them as mail is a wrong answer that looks right.**
  A folder walk that does not say which folder each item came from will report drafts, sent items
  and calendar invitations as if they were correspondence. Name the folder.
- **`ReceivedTime` and `SenderName` can be written, in one specific order.** They are read-only on
  the object model, and `PropertyAccessor` accepts them and appears to do nothing — which is what
  made an earlier note here say they could not be set at all. What that note had wrong was the
  order. Set them on a *new, unsaved* item, clear `MSGFLAG_UNSENT` first, then save once:

  ```powershell
  $m = $folder.Items.Add(0)
  $m.Subject = "..."
  $pa = $m.PropertyAccessor
  $pa.SetProperty("http://schemas.microsoft.com/mapi/proptag/0x0E070003", 1)          # MESSAGE_FLAGS
  $pa.SetProperty("http://schemas.microsoft.com/mapi/proptag/0x0C1A001F", "Name")     # SENDER_NAME
  $pa.SetProperty("http://schemas.microsoft.com/mapi/proptag/0x0C1F001F", "a@b.c")    # SENDER_EMAIL
  $pa.SetProperty("http://schemas.microsoft.com/mapi/proptag/0x00390040", $utc)       # SUBMIT_TIME
  $pa.SetProperty("http://schemas.microsoft.com/mapi/proptag/0x0E060040", $utc)       # DELIVERY_TIME
  $m.Save()
  ```

  Verified on 2026-08-23: asking for 09:14:22 and reading back 12:14:22 in a UTC+3 zone. **The
  value is stored as written and displayed as local**, so pass UTC or the mail arrives with the
  offset added twice.
- **`.eml` cannot be imported.** `Namespace.OpenSharedItem` answers "Invalid path or URL" for one:
  it takes `.msg`, `.vcf` and `.ics` and nothing else. The path is not the problem, so do not go
  shortening it.
- **To put mail in a chosen folder, create a post and rewrite its class.** A post is written where
  it is created rather than routed to Drafts, and `PR_MESSAGE_CLASS` turns it into mail
  afterwards. No `Move()`, so this works even where the default store is broken:

  ```powershell
  $m = $folder.Items.Add(6)                                                  # olPostItem
  $m.Subject = "..."; $m.Body = "..."
  $pa = $m.PropertyAccessor
  $pa.SetProperty("http://schemas.microsoft.com/mapi/proptag/0x001A001F", "IPM.Note")
  # ...then the flags, sender and times above, then one Save().
  ```

  **Restart Outlook before believing what you read back.** In the session that wrote them the
  items still report `Class = 45` (post) while `MessageClass` already says `IPM.Note`; after a
  restart all of them report `Class = 43`. A walk that filters on `Class -ne 43` therefore skips
  almost everything it just wrote. Verified on 2026-08-23 with 156 seeded messages: 101 of 111
  read as posts before the restart and 111 of 111 as mail after it.
- **`Delete()` moves an item to Deleted Items of the default store**, so it fails whenever that
  store is the broken one. `Items.Remove(index)` is the other route.
- **`Move()` goes through the default store too**, and fails the same way: "The set of folders
  cannot be opened. The file ... cannot be found." A broken default store therefore makes it
  impossible to put a message anywhere — which, combined with the Drafts routing above, means no
  mail can be placed in a chosen folder at all until the profile is repaired. Check
  `Namespace.DefaultStore.DisplayName` early: an empty answer is the broken one.

---

## Teams

The current client is a WebView2 app (`MSTeams` MSIX, process `ms-teams`). There is no COM. Its
data is a Chromium IndexedDB, values serialised by V8, and `read_leveldb` opens it directly:

```
%LOCALAPPDATA%\Packages\MSTeams_8wekyb3d8bbwe\LocalCache\Microsoft\MSTeams\EBWebView\
    WV2Profile_tfl\IndexedDB\https_teams.live.com_0.indexeddb.leveldb\      personal account
    WV2Profile_tfw\IndexedDB\https_teams.microsoft.com_0.indexeddb.leveldb\ work or school
```

**Do not go looking for the data with a file search.** The whole `EBWebView` tree is thousands of
cache, shader and service-worker files, and walking it takes minutes and finds nothing — the store
itself is the `.leveldb` directory named above, and on this machine it was six files and 1.4 MB
holding 32,241 records, which `read_leveldb` scans in about a fifth of a second. Point the tool at
that directory. Anything slower means the wrong path.

Calendar events are in there as whole JSON payloads — `subject`, `startTime`, `endTime`,
`organizerId`, `meetingType`, `eventRecurrencePattern`, `isCancelled`, `iCalUid`, `exchangeId` and
the join URL — so a meeting that never touched Outlook is still reachable locally. Filtering with
`contains` on a subject, on `"meeting"` or on a date is far cheaper than reading the store whole.

- **Teams keeps the files open, and that is fine.** `read_leveldb` reads with every share flag set
  and works with Teams running. Never ask the user to quit it.
- **`exchangeId` is the join between the two worlds.** A meeting present in both Teams and the
  Exchange calendar carries it, which is how to reconcile the two without matching on subject text.
- **Deleted and superseded records are still there.** The reader does not resolve tombstones or
  sequence numbers, so the same meeting can appear several times, in older and newer forms. Read
  the newest, and never report a count of records as a count of meetings.
- **Dates come in two shapes.** The JSON payloads carry ISO 8601 strings. Elsewhere V8 writes a
  date as the tag `D` followed by eight little-endian bytes: milliseconds since the epoch.
- **The schema is undocumented and changes.** Parsers written for Teams 1.x return nothing at all
  on the current client. Treat what is read here as evidence that may stop being true after an
  update, and say so when reporting from it.
- **A block count of skipped blocks is not noise.** `read_leveldb` says when a block used a
  compression it cannot undo — Chromium has been moving towards zstd. Zero found *and* blocks
  skipped means "unknown", not "empty".

### Which meetings end up where

| booked | in the Exchange calendar | reachable by COM |
|---|---|---|
| in Outlook | yes | yes |
| in Teams, work account | yes — Teams writes to Exchange | yes |
| in Teams, personal account | no Exchange at all | only the IndexedDB above |
| an ad-hoc call or "meet now" | no calendar item exists | neither; only Graph call records |

So "not everything is booked" splits in two: meetings *scheduled* in Teams on a work account do
reach Exchange and the object model sees them. Only spontaneous calls are genuinely missing.

---

## Before reading a real mailbox

On macOS the equivalent data is masked — subjects and senders arrive as `pii:<hash>`. Here they
are not. COM returns real subject lines, real addresses, real body text, and whatever passes
through the session goes wherever the model runs.

Ask the owner before running any of this against a work mailbox, and prefer a local model. If
they want an aggregate, produce the aggregate on the machine and let only that reach the prompt —
subject lines are the content, not the metadata.

---

## Running any of this

These are PowerShell one-liners, so they need `run_command`, which is not classified read-only and
will ask for approval. Shell scope has to reach outside the workspace. Long COM calls can block:
give them a timeout and expect to have to say why nothing came back.

`read_leveldb` is the exception: it is a read, needs no shell and no approval beyond read scope,
and does not care whether Teams is running. Reach for it before any PowerShell against Teams.

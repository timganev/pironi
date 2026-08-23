# windows-teams

description: Where the Teams client keeps its data on Windows, how to open it, and what it will lie to you about.
triggers: teams, msteams, тиймс, leveldb, indexeddb, ebwebview, webview2, чат, чатове, chats

Where the mail and the Exchange calendar live is a separate question — that is `windows-outlook`.
This is only the Teams client's own store. Which of the two holds a given meeting is answered in
`windows-outlook`, under "Meetings booked in Teams"; call `read_skill` with `windows-outlook` when
the question is really about the calendar.

## The store

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

## What it will lie to you about

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

## Running it

`read_leveldb` needs no shell and no approval beyond read scope, and does not care whether Teams is
running. Reach for it before any PowerShell against Teams.

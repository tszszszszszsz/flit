# PadSearch — bounded search over pad lines

`PadSearch` is a JVM-pure search over the lines of a supplied `PadView`. It
answers one question: where does a case-insensitive query occur in the text of
each line, up to a fixed number of results? It returns offsets only. It does
not color, mark, snippet, escape or otherwise render a match, and it never
holds a view, a query or a result between calls.

The module lives in the protocol source set in package
`app.flit.share.protocol.ideas.padsearch` and imports only `PadView` from the
Circle pad core. It reads no global state, clock, filesystem, socket or network.
`PadUi.search` projects its hits onto the pad card; the core itself holds no
app state and adds no route, header, DTO field, persisted store field or schema
change.

## Inputs and output

`PadSearch.search(view, query, limit)` takes a `PadView`, a query string and an
optional result limit. It returns a `Result` with four fields:

- `hits`: a list of `Hit(lineIndex, key, start, end)`. `lineIndex` is the index
  of the line inside `view.lines`; `key` is copied from that line;
  `start` is inclusive and `end` is exclusive.
- `scannedLines`: how many lines were visited, never more than
  `MAX_LINES_SCANNED`.
- `truncated`: whether any line, text tail or match was hidden by a cap.
- `queryRejected`: whether the query was empty or longer than
  `MAX_QUERY_CHARS`; a rejected query runs no scan at all.

`start` and `end` are UTF-16 code-unit indices into the original, unmodified
`PadLine.text`. They are not code-point indices and not indices into a
lowercased copy. A caller that wants the matched text can slice the original
line with them; the search itself keeps no text from the match. A whitespace
query is an ordinary query, so a single space can be found in a line.

## Matching

Case-insensitivity is the `ignoreCase` character comparison of the Kotlin
standard library, applied one UTF-16 code unit at a time. No locale is read
and no Unicode normalization is performed. Because comparisons are
character-by-character, a matched substring always has the same code-unit
length as the query; folding a line never shifts an offset. A match that is
found at index `i` ends exactly at `i + query.length`. Matches on a line do not
overlap: after a match the scan resumes at its end.

Only line text is searched. Keys, author fingerprints, lamport values and
event ids are not searched, though the key is copied into each hit for
identification. Hits are ordered by ascending `lineIndex` and then by ascending
`start`, which is the order the lines are already in and the order the scan
visits offsets. Repeating a call on the same arguments gives the same result.

## Bounds

| Constant | Value | Effect |
| --- | --- | --- |
| `MAX_QUERY_CHARS` | 128 | Longer queries are rejected without a scan. |
| `MAX_TEXT_SCAN_CHARS` | 4096 | Only this leading prefix of each line text is scanned. |
| `MAX_LINES_SCANNED` | 256 | Only this many leading lines of the view are visited. |
| `MAX_HITS` | 64 | Hard ceiling on returned hits. |

`limit` is clamped into `0..MAX_HITS`. A negative limit returns no hits; a
larger one cannot exceed `MAX_HITS`. A limit of zero returns no hits and marks
the result truncated. Bounded text and bounded line counts put an upper bound
on the scan work no matter how large the supplied view is. The constants match
the pad core's own key, text and line limits where those exist, so a view that
`PadCore.view` produced normally stays inside the search window.

`truncated` is set when the view has more lines than `MAX_LINES_SCANNED`, when
any scanned line has more text than `MAX_TEXT_SCAN_CHARS`, or when a match is
found after the result cap is full. A match that begins inside the text window
but ends beyond it is not returned, because the window is cut before matching.
A clean scan that finds every match inside the caps reports `truncated = false`.

## Totality

The function is total on hostile input. An empty or over-cap query, an empty
view, blank or lone-surrogate text, an empty key and oversized lines all return
a bounded result. The guard inside `indexOf` only calls the standard library
with a start index for which the remaining query can still fit, so no index
error is possible from a well-formed list. A line collection that throws while
being read is caught at the entry point and yields an empty result marked
truncated. No exception, mutation of the view, or retained state escapes a
call.

## Limitations

- Only the first `MAX_TEXT_SCAN_CHARS` code units of each line are searched.
  A match that lies past that prefix is not reported; `truncated` says so.
- Only the first `MAX_LINES_SCANNED` lines are visited. Lines beyond that are
  not reported; `truncated` says so.
- At most `MAX_HITS` hits are returned. Later matches are counted only enough
  to set `truncated`; they are not collected.
- Offsets are UTF-16 code units in the unsorted, unnormalized original text.
  A caller that needs grapheme clusters or code-point offsets must convert
  them outside this module.
- The standard library's `ignoreCase` comparison does not perform full Unicode
  case folding. Scripts whose case mapping changes length, such as the German
  sharp s against a double s, do not match across those forms. This is a
  property of the comparison, not of the offsets.
- Matching is plain substring matching. There are no word boundaries, regular
  expressions, fuzzy matching, ranking or scoring, and no search of keys or
  metadata.
- The module does not verify signatures, replay windows or certificates. It
  searches whatever view it is handed. A view built from unverified events is
  searched as data, with no claim about its origin.
- No ordering guarantee is made for a view whose `lines` list is not already
  in a stable order: the search returns hits in the order that list supplies.

## Verification boundary

The pure test class `PadSearchTest` covers case-insensitive matching, offsets
into original text, ordering, the query gate, every cap, determinism and
hostile input. It uses no fixture outside the Kotlin test library and the
protocol sources. Nothing in this module needs a running node, a transport or
a device observation.

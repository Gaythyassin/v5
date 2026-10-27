Corrupted HTTP access-log fixtures
==================================

Files
-----
- data/access-dirty.log: deliberately mixed good and malformed HTTP access-log records.
- data/access-empty.log: a zero-byte file for empty-input handling.

Intended test cases in access-dirty.log
---------------------------------------
1. Three valid records (expected to reach the main output sink):
   - GET /index.html (200, 1234 bytes)
   - POST /api/login (401, 287 bytes)
   - GET /images/logo.png (304, 0 bytes)
2. Two truncated records (one incomplete timestamp; one missing response-size and trailing fields).
3. One negative response-byte count (-45).
4. One timestamp from 1970.
5. One timestamp from 2099.
6. One record containing non-UTF-8 bytes (0xFF 0xFE 0xFA) in the user-agent field.
7. One 100 KiB single line (102,400 bytes before the line terminator).

Recommended acceptance checks
-----------------------------
- The pipeline completes without crashing.
- The three valid records are emitted to the main sink.
- Every invalid record is either skipped and counted or written to the dead-letter sink,
  according to the configured policy.
- The summary counts reconcile: records read = records emitted + records dropped +
  records dead-lettered (adapt the equation if the implementation counts the header/comment
  or line-level parse failures differently).
- The empty file is handled cleanly with zero data records and no crash.

Notes
-----
- The timestamps 1970 and 2099 are syntactically valid HTTP-log timestamps; whether they are
  invalid depends on the project's configured timestamp-sanity range. Configure a range that
  rejects them if the acceptance test expects them to be treated as bad.
- The 100 KiB line is structurally parseable only if the pipeline permits that record size;
  otherwise it should be rejected through the configured error policy.
- The non-UTF-8 bytes are intentional; the reader should handle decoding errors according to
  the chosen error policy rather than silently corrupting the record.

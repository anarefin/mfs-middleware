# AML vendor files (DRAFT, ticket 123; ticket 125 finalises)

The files exchanged with the AML vendor over SFTP (FRS EXT-05). Drafted by ticket 123 so that the AML
vendor mock of `mw-simulators` (party `aml`) has a format to answer; ticket 125 (`mw-compliance`) finalises
it, adds the owner entry `files/aml/...: mw-compliance` to `contracts/OWNERS.yml` and the machine-checked
schema files, and the mock follows whatever it decides. Until then this README is the only file here (the
contracts lint skips READMEs, so no owner is needed yet).

Encoding UTF-8, lines end with LF, fields separated by `|`, no quoting (no field may contain `|` or a line
break). Amounts are integer poisha. Times are ISO-8601 with the Asia/Dhaka offset (`+06:00`).

## Transaction file (middleware -> vendor), folder `aml/in`

Name `AML_TXN_<yyyyMMddHH>.txt` (the hour of the feed window, Asia/Dhaka).

| Record | Fields |
| --- | --- |
| Header (first line) | `H`, `AML_TXN`, format version `1`, file id, created at |
| Detail (one per transaction) | `D`, txnId, type, channel, payer `<partyType>:<id>`, payee `<partyType>:<id>`, amount (poisha), time |
| Trailer (last line) | `T`, row count, total amount (poisha), lower-case hex SHA-256 of every byte before the trailer line |

```
H|AML_TXN|1|F-2026112010|2026-11-20T10:00:00+06:00
D|0196a1b2-0000-7000-8000-000000000001|P2P|APP|WALLET:01712345678|WALLET:01812345678|10000|2026-11-20T09:00:00+06:00
T|1|10000|<sha256 hex of the two lines above, each with its LF>
```

Open point for ticket 125: FRS / ticket 125 say "masked/tokenised parties". The mock's MSISDN rules (watch
list, ending `6666`) need the clear MSISDN of `WALLET` / `MSISDN` parties; if 125 tokenises them, the
watch-list must hold tokens and the suffix rule moves to a token-side marker. A file to a real vendor is
screened on the identity, so clear identities over the vendor's SFTP are the likely outcome.

## Response file (vendor -> middleware), folder `aml/out`

Name: the transaction file's name with `AML_TXN_` replaced by `AML_RESP_` (any other name gets `.resp`).

| Record | Fields |
| --- | --- |
| Header | `H`, `AML_RESP`, format version `1`, the transaction file's name, its SHA-256 |
| Action (one per flagged transaction) | `ALERT` / `HOLD` / `BLOCK`, txnId, walletId (`-` when none), rule, reference |
| Error (instead of actions, when the file was unreadable) | `E`, code (`BAD_HEADER`, `BAD_ROW`, `BAD_TRAILER`, `COUNT_MISMATCH`, `TOTAL_MISMATCH`, `CHECKSUM_MISMATCH`), detail |
| Trailer | `T`, action line count, alerts, holds, blocks |

```
H|AML_RESP|1|AML_TXN_2026112010.txt|<sha256 of the transaction file>
HOLD|0196a1b2-0000-7000-8000-000000000003|01711000777|WATCH_LIST|AML-1a2b3c4d-1
T|1|0|1|0
```

Mock rules (seed `services/mw-simulators/src/main/resources/seed/v1/aml/rules.json`): amount above
`alertAboveMinor` -> ALERT `AMOUNT_THRESHOLD` on the payer; a party on the watch-list -> HOLD `WATCH_LIST`;
a party ending `blockSuffix` (`6666`) -> BLOCK `BLOCK_SUFFIX`; one line per transaction, the strongest wins
(BLOCK, HOLD, ALERT; payer before payee). Reference `AML-<first 8 hex of the transaction file's SHA-256>-<n>`.

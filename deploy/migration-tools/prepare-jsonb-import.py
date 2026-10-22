#!/usr/bin/env python3
"""Convert mongoexport Extended-JSON files to a PostgreSQL JSONB CSV import.

This utility has no MongoDB client dependency. Run it against offline exports created
before the old datastore is shut down.
"""
from __future__ import annotations

import argparse
import csv
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

# (PostgreSQL JSONB collection, legacy MongoDB export filename without suffix).
# Several old @Document classes relied on Spring Data's default camelCase collection names.
COLLECTION_EXPORTS = (
    ("posts", "posts"),
    ("comments", "comments"),
    ("likes", "likes"),
    ("reposts", "reposts"),
    ("chat_messages", "chat_messages"),
    ("user_blocks", "userBlock"),
    ("follow_requests", "followRequest"),
    ("saved_posts", "savedPost"),
    ("comment_reactions", "commentReaction"),
    ("content_reports", "contentReport"),
    ("post_revisions", "postRevision"),
    ("account_security", "accountSecurity"),
    ("account_cleanup_jobs", "account_cleanup_jobs"),
    ("post_media_assets", "post_media_assets"),
)

# These model properties are java.time.LocalDateTime and must not retain the
# timezone suffix from BSON Date's UTC representation. All other BSON dates are
# kept as UTC ISO-8601 strings for java.time.Instant properties.
LOCAL_DATETIME_FIELDS = {
    "posts": {"createdAt", "updatedAt", "moderationLeaseUntil", "moderationLastAttemptAt", "mediaCleanupQueuedAt"},
    "comments": {"createdAt", "moderationLeaseUntil", "moderationLastAttemptAt"},
    "likes": {"createdAt"},
    "reposts": {"createdAt"},
    "chat_messages": {"sentAt", "deliveredAt"},
    "post_media_assets": {"uploadedAt", "updatedAt", "deletedAt"},
}


def read_export(path: Path) -> Iterable[dict[str, Any]]:
    raw = path.read_text(encoding="utf-8-sig").strip()
    if not raw:
        return []
    try:
        parsed = json.loads(raw)
        if isinstance(parsed, list):
            return parsed
        if isinstance(parsed, dict):
            return [parsed]
        raise ValueError(f"{path} must contain a JSON object or array of objects")
    except json.JSONDecodeError:
        rows: list[dict[str, Any]] = []
        for line_number, line in enumerate(raw.splitlines(), 1):
            if line.strip():
                item = json.loads(line)
                if not isinstance(item, dict):
                    raise ValueError(f"{path}:{line_number}: each JSONL line must be an object")
                rows.append(item)
        return rows


def parse_datetime(raw: Any) -> datetime:
    if isinstance(raw, dict) and "$numberLong" in raw:
        return datetime.fromtimestamp(int(raw["$numberLong"]) / 1000, tz=timezone.utc)
    if not isinstance(raw, str):
        raise ValueError(f"Unsupported Extended JSON $date value: {raw!r}")
    value = raw.replace("Z", "+00:00")
    parsed = datetime.fromisoformat(value)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def normalize(value: Any, collection: str, field_path: tuple[str, ...] = ()) -> Any:
    if isinstance(value, list):
        return [normalize(item, collection, field_path + ("[]",)) for item in value]
    if isinstance(value, dict):
        if len(value) == 1 and "$oid" in value:
            return str(value["$oid"])
        if len(value) == 1 and "$numberInt" in value:
            return int(value["$numberInt"])
        if len(value) == 1 and "$numberLong" in value:
            return int(value["$numberLong"])
        if len(value) == 1 and "$numberDouble" in value:
            return float(value["$numberDouble"])
        if len(value) == 1 and "$numberDecimal" in value:
            # JSON numbers preserve the numeric comparison semantics used by PostgreSQL.
            return float(value["$numberDecimal"])
        if len(value) == 1 and "$date" in value:
            parsed = parse_datetime(value["$date"])
            leaf = field_path[-1] if field_path else ""
            is_local_datetime = leaf in LOCAL_DATETIME_FIELDS.get(collection, set())
            # PostRevision.createdAt is Instant even when the revision is nested in Post.editHistory.
            if collection == "posts" and "editHistory" in field_path and leaf == "createdAt":
                is_local_datetime = False
            if is_local_datetime:
                return parsed.replace(tzinfo=None).isoformat(timespec="microseconds").rstrip("0").rstrip(".")
            return parsed.isoformat(timespec="microseconds").replace("+00:00", "Z").rstrip("0").replace(".Z", "Z")
        normalized: dict[str, Any] = {}
        for key, child in value.items():
            # Mongo's primary key becomes the model's ordinary `id` property.
            if key == "_id":
                normalized["id"] = normalize(child, collection, field_path + ("id",))
            else:
                normalized[key] = normalize(child, collection, field_path + (key,))
        return normalized
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", type=Path, required=True, help="Directory containing <collection>.json exports")
    parser.add_argument("--output-file", type=Path, required=True, help="Output CSV used by import-jsonb-to-postgres.sh")
    parser.add_argument("--require-all", action="store_true", help="Fail if any known collection export is missing")
    args = parser.parse_args()

    if not args.input_dir.is_dir():
        parser.error(f"Input directory does not exist: {args.input_dir}")
    args.output_file.parent.mkdir(parents=True, exist_ok=True)
    total = 0
    counts: dict[str, int] = {}
    with args.output_file.open("w", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=("collection_name", "document_id", "payload"), quoting=csv.QUOTE_MINIMAL)
        writer.writeheader()
        for collection, export_name in COLLECTION_EXPORTS:
            source = args.input_dir / f"{export_name}.json"
            if not source.exists():
                if args.require_all:
                    raise FileNotFoundError(f"Required legacy collection export is missing: {source}")
                continue
            count = 0
            for document in read_export(source):
                if not isinstance(document, dict):
                    raise ValueError(f"{source}: each document must be a JSON object")
                if "_id" not in document:
                    raise ValueError(f"{source}: document is missing _id")
                document_id = normalize(document["_id"], collection, ("id",))
                normalized = normalize(document, collection)
                if "id" not in normalized:
                    normalized["id"] = document_id
                payload = json.dumps(normalized, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
                writer.writerow({"collection_name": collection, "document_id": str(document_id), "payload": payload})
                count += 1
                total += 1
            counts[collection] = count

    for collection, count in counts.items():
        print(f"{collection}: {count}")
    print(f"Prepared {total} JSONB rows at {args.output_file}")
    if total == 0:
        print("Warning: no records were exported; this will import an empty dataset.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

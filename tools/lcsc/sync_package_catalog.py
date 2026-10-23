#!/usr/bin/env python3
"""Build the offline LCSC category/brand/package catalog used by EPrint.

The script reads a user-provided electronic leaf-category snapshot, calls the
public category parameter endpoint with conservative throttling, and writes a
compact, deterministic Android asset. A small per-category cache makes the job
resumable without retaining product records or other unrelated data.
"""

from __future__ import annotations

import argparse
import json
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ENDPOINT = "https://list.szlcsc.com/parameter"
CATALOG_URL = "https://www.szlcsc.com/catalog.html"
USER_AGENT = "EPrint-LCSC-Catalog/2.0"


class RequestRateLimiter:
    def __init__(self, interval_seconds: float) -> None:
        self._interval = max(0.0, interval_seconds)
        self._lock = threading.Lock()
        self._next_start = 0.0

    def wait(self) -> None:
        with self._lock:
            now = time.monotonic()
            delay = max(0.0, self._next_start - now)
            self._next_start = max(now, self._next_start) + self._interval
        if delay:
            time.sleep(delay)


def request_body(catalog_id: int) -> dict[str, Any]:
    return {
        "currentPage": 1,
        "pageSize": 30,
        "catalogIdFilter": catalog_id,
        "brandIdFilter": "",
        "standardFilter": "",
        "brandPlaceFilter": "",
        "brandOriginFilter": "",
        "labelFilter": "",
        "arrangeFilter": "",
        "smtLabelFilter": "",
        "spotFilter": 1,
        "discountFilter": 1,
        "startPrice": "",
        "endPrice": "",
        "sortNumber": 0,
        "queryParameterValue": "",
        "lastParamName": "",
        "keyword": "",
        "secondKeyword": "",
        "hasDataFile": False,
        "demandNumber": "",
        "satisfyStockType": "",
        "queryProductTypeCode": "",
    }


def extract_catalog_facets(
    payload: dict[str, Any], catalog_id: int
) -> tuple[list[str], list[dict[str, str]]]:
    if payload.get("code") != 200:
        raise RuntimeError(
            f"catalog {catalog_id}: endpoint returned code={payload.get('code')} "
            f"msg={payload.get('msg')!r}"
        )
    groups = payload.get("result", {}).get("paramGroupMap", {})
    package_group = groups.get("queryProductStandard") or {}
    if package_group.get("paramName") not in (None, "封装/规格"):
        raise RuntimeError(
            f"catalog {catalog_id}: queryProductStandard changed to "
            f"{package_group.get('paramName')!r}"
        )
    packages: list[str] = []
    seen: set[str] = set()
    for item in package_group.get("paramDetailList") or []:
        if item.get("status") is False:
            continue
        name = str(item.get("name") or "").strip()
        if name and name not in seen:
            seen.add(name)
            packages.append(name)
    brand_group = groups.get("queryProductGradePlateId") or {}
    if brand_group.get("paramName") not in (None, "品牌"):
        raise RuntimeError(
            f"catalog {catalog_id}: queryProductGradePlateId changed to "
            f"{brand_group.get('paramName')!r}"
        )
    brands: list[dict[str, str]] = []
    brand_names_by_code: dict[str, str] = {}
    for item in brand_group.get("paramDetailList") or []:
        if item.get("status") is False:
            continue
        name = str(item.get("name") or "").strip()
        code = str(item.get("code") or "").strip()
        if not name:
            continue
        if not code:
            raise RuntimeError(f"catalog {catalog_id}: brand {name!r} has no code")
        previous = brand_names_by_code.get(code)
        if previous is not None and previous != name:
            raise RuntimeError(
                f"catalog {catalog_id}: brand code {code!r} maps to both "
                f"{previous!r} and {name!r}"
            )
        if previous is None:
            brand_names_by_code[code] = name
            brands.append({"code": code, "name": name})
    return packages, brands


def fetch_category(
    category: dict[str, Any],
    cache_dir: Path,
    limiter: RequestRateLimiter,
    timeout_seconds: float,
    retries: int,
    refresh: bool,
) -> tuple[int, list[str], list[dict[str, str]], bool]:
    catalog_id = int(category["catalogId"])
    cache_path = cache_dir / f"{catalog_id}.json"
    if cache_path.exists() and not refresh:
        cached = json.loads(cache_path.read_text(encoding="utf-8"))
        # v1 cache entries only contained packages. Re-fetch them once so a
        # non-refresh resume cannot silently produce a brand-empty v2 asset.
        if cached.get("schemaVersion") == 2 and "brands" in cached:
            return (
                catalog_id,
                list(cached.get("packages") or []),
                list(cached.get("brands") or []),
                True,
            )

    encoded = json.dumps(
        request_body(catalog_id), ensure_ascii=False, separators=(",", ":")
    ).encode("utf-8")
    request = urllib.request.Request(
        ENDPOINT,
        data=encoded,
        method="POST",
        headers={
            "Accept": "application/json",
            "Content-Type": "application/json",
            "Origin": "https://list.szlcsc.com",
            "Referer": f"https://list.szlcsc.com/catalog/{catalog_id}.html",
            "User-Agent": USER_AGENT,
        },
    )
    last_error: Exception | None = None
    for attempt in range(retries + 1):
        limiter.wait()
        try:
            with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
                payload = json.loads(response.read().decode("utf-8"))
            packages, brands = extract_catalog_facets(payload, catalog_id)
            cache_path.parent.mkdir(parents=True, exist_ok=True)
            cache_path.write_text(
                json.dumps(
                    {
                        "schemaVersion": 2,
                        "catalogId": catalog_id,
                        "packages": packages,
                        "brands": brands,
                    },
                    ensure_ascii=False,
                    separators=(",", ":"),
                ),
                encoding="utf-8",
            )
            return catalog_id, packages, brands, False
        except (OSError, ValueError, RuntimeError, urllib.error.HTTPError) as error:
            last_error = error
            if attempt < retries:
                time.sleep(min(16.0, 2.0**attempt))
    raise RuntimeError(f"catalog {catalog_id}: {last_error}") from last_error


def build_snapshot(
    categories: list[dict[str, Any]],
    packages_by_id: dict[int, list[str]],
    brands_by_id: dict[int, list[dict[str, str]]],
) -> dict[str, Any]:
    package_pool = sorted(
        {package for values in packages_by_id.values() for package in values},
        key=lambda value: (value.casefold(), value),
    )
    package_ids = {package: index for index, package in enumerate(package_pool)}
    brand_names_by_code: dict[str, str] = {}
    for values in brands_by_id.values():
        for brand in values:
            code = str(brand["code"])
            name = str(brand["name"])
            previous = brand_names_by_code.get(code)
            if previous is not None and previous != name:
                raise RuntimeError(
                    f"brand code {code!r} maps to both {previous!r} and {name!r}"
                )
            brand_names_by_code[code] = name
    brand_pool = [
        {"code": code, "name": name}
        for code, name in sorted(
            brand_names_by_code.items(),
            key=lambda item: (item[1].casefold(), item[1], item[0]),
        )
    ]
    brand_ids = {brand["code"]: index for index, brand in enumerate(brand_pool)}
    output_categories: list[dict[str, Any]] = []
    package_relation_count = 0
    brand_relation_count = 0
    categories_with_packages = 0
    categories_with_brands = 0
    for category in sorted(categories, key=lambda item: int(item["catalogId"])):
        catalog_id = int(category["catalogId"])
        values = packages_by_id[catalog_id]
        brand_values = brands_by_id[catalog_id]
        package_relation_count += len(values)
        brand_relation_count += len(brand_values)
        if values:
            categories_with_packages += 1
        if brand_values:
            categories_with_brands += 1
        output_categories.append(
            {
                "catalogId": catalog_id,
                "catalogCode": category.get("catalogCode") or "",
                "name": category.get("categoryName") or "",
                "nameEn": category.get("categoryNameEn") or "",
                "parentName": category.get("parentName") or "",
                "rootName": category.get("rootName") or "",
                "mainCategoryName": category.get("mainCategoryName") or "",
                "path": category.get("path") or [],
                "productCount": int(category.get("productCount") or 0),
                "packageIds": [package_ids[value] for value in values],
                "brandIds": [brand_ids[str(value["code"])] for value in brand_values],
            }
        )
    return {
        "schemaVersion": 2,
        "source": {
            "name": "立创商城（LCSC）",
            "catalogUrl": CATALOG_URL,
            "parameterEndpoint": ENDPOINT,
            "scope": "电子元器件末级品类；排除 mainCategoryCode=mro",
            "retrievedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        },
        "statistics": {
            "categoryCount": len(output_categories),
            "categoriesWithPackages": categories_with_packages,
            "categoriesWithoutPackages": len(output_categories)
            - categories_with_packages,
            "categoriesWithBrands": categories_with_brands,
            "categoriesWithoutBrands": len(output_categories) - categories_with_brands,
            "uniquePackageCount": len(package_pool),
            "uniqueBrandCount": len(brand_pool),
            "categoryPackageRelationCount": package_relation_count,
            "categoryBrandRelationCount": brand_relation_count,
        },
        "packages": package_pool,
        "brands": brand_pool,
        "categories": output_categories,
    }


def parse_args() -> argparse.Namespace:
    project_root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--categories",
        type=Path,
        default=project_root / "tools/lcsc/electronic_categories.json",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=project_root
        / "apps/android/app/src/main/assets/catalog/lcsc_category_packages.json",
    )
    parser.add_argument(
        "--cache-dir",
        type=Path,
        default=project_root / "tools/lcsc/cache/parameter",
    )
    parser.add_argument("--workers", type=int, default=2)
    parser.add_argument("--delay-ms", type=int, default=400)
    parser.add_argument("--timeout-seconds", type=float, default=45.0)
    parser.add_argument("--retries", type=int, default=4)
    parser.add_argument("--refresh", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.categories.is_file():
        raise SystemExit(
            "Category snapshot is not distributed with the source. Supply an "
            "authorized local snapshot using --categories; see docs/BUILDING.md."
        )
    category_snapshot = json.loads(args.categories.read_text(encoding="utf-8"))
    categories = list(category_snapshot.get("categories") or [])
    if not categories:
        raise SystemExit(f"No categories found in {args.categories}")
    if int(category_snapshot.get("categoryCount") or 0) != len(categories):
        raise SystemExit("Category snapshot count does not match its contents")

    limiter = RequestRateLimiter(args.delay_ms / 1000.0)
    packages_by_id: dict[int, list[str]] = {}
    brands_by_id: dict[int, list[dict[str, str]]] = {}
    cached_count = 0
    failures: list[str] = []
    with ThreadPoolExecutor(max_workers=max(1, args.workers)) as executor:
        futures = {
            executor.submit(
                fetch_category,
                category,
                args.cache_dir,
                limiter,
                args.timeout_seconds,
                max(0, args.retries),
                args.refresh,
            ): category
            for category in categories
        }
        for completed, future in enumerate(as_completed(futures), start=1):
            category = futures[future]
            try:
                catalog_id, packages, brands, cached = future.result()
                packages_by_id[catalog_id] = packages
                brands_by_id[catalog_id] = brands
                cached_count += int(cached)
            except Exception as error:  # keep collecting errors for a useful summary
                failures.append(f"{category.get('catalogId')} {category.get('categoryName')}: {error}")
            if completed % 10 == 0 or completed == len(futures):
                print(
                    f"[{completed}/{len(futures)}] cached={cached_count} "
                    f"failed={len(failures)}",
                    flush=True,
                )

    if failures:
        raise SystemExit(
            "LCSC catalog is incomplete; output was not replaced:\n" + "\n".join(failures)
        )
    snapshot = build_snapshot(categories, packages_by_id, brands_by_id)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(snapshot, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(snapshot["statistics"], ensure_ascii=False, indent=2))
    print(f"Wrote {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

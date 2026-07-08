#!/usr/bin/env python3
import json
import os
import sys
from datetime import datetime, timezone


def load_result(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def format_number(value):
    if value is None:
        return "N/A"
    if isinstance(value, float):
        return f"{value:.2f}"
    return str(value)


def generate_html(result, output_path, svg_path=None):
    platform = result.get("platform", "unknown")
    native_version = result.get("nativeVersion", "unknown")
    commit_sha = result.get("commitSha") or "unknown"
    runner_os = result.get("runnerOs", "unknown")
    runner_arch = result.get("runnerArch", "unknown")
    cpu_model = result.get("cpuModel", "unknown")
    cpu_flags = result.get("cpuFlags", "")
    timestamp = result.get("timestamp", datetime.now(timezone.utc).isoformat())

    benchmarks = result.get("benchmarks", [])
    rows = []
    for bench in benchmarks:
        name = bench.get("name", "")
        metrics = bench.get("metrics", {})
        iterations = metrics.get("iterations", 0)
        elapsed_ms = metrics.get("elapsedMs", 0)
        ops_per_second = metrics.get("opsPerSecond", 0)
        ns_per_op = metrics.get("nsPerOp", 0)
        total_matches = metrics.get("totalMatches", 0)
        rows.append(
            f"<tr><td>{name}</td><td>{format_number(iterations)}</td>"
            f"<td>{format_number(elapsed_ms)}</td><td>{format_number(ops_per_second)}</td>"
            f"<td>{format_number(ns_per_op)}</td><td>{format_number(total_matches)}</td></tr>"
        )

    svg_section = ""
    if svg_path:
        svg_filename = os.path.basename(svg_path)
        svg_section = f'<h2>Charts</h2><img src="{svg_filename}" alt="Benchmark charts" />'

    html = f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>hyperscan-java-panama Performance Report</title>
    <style>
        body {{ font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; margin: 2rem; color: #333; }}
        h1, h2 {{ color: #1a1a1a; }}
        table {{ border-collapse: collapse; width: 100%; margin-top: 1rem; }}
        th, td {{ border: 1px solid #ddd; padding: 0.6rem; text-align: left; }}
        th {{ background-color: #f4f4f4; }}
        tr:nth-child(even) {{ background-color: #fafafa; }}
        .meta {{ margin-bottom: 1.5rem; }}
        .meta p {{ margin: 0.2rem 0; }}
        code {{ background-color: #f0f0f0; padding: 0.1rem 0.3rem; border-radius: 3px; }}
        img {{ max-width: 100%; height: auto; border: 1px solid #ddd; }}
    </style>
</head>
<body>
    <h1>hyperscan-java-panama Performance Report</h1>
    <div class="meta">
        <p><strong>Platform:</strong> <code>{platform}</code></p>
        <p><strong>Native Version:</strong> <code>{native_version}</code></p>
        <p><strong>Commit:</strong> <code>{commit_sha}</code></p>
        <p><strong>Runner OS:</strong> {runner_os}</p>
        <p><strong>Runner Arch:</strong> {runner_arch}</p>
        <p><strong>CPU Model:</strong> {cpu_model}</p>
        <p><strong>Timestamp:</strong> {timestamp}</p>
    </div>

    <h2>Benchmark Results</h2>
    <table>
        <thead>
            <tr>
                <th>Name</th>
                <th>Iterations</th>
                <th>Elapsed (ms)</th>
                <th>Ops/Second</th>
                <th>ns/Op</th>
                <th>Total Matches</th>
            </tr>
        </thead>
        <tbody>
            {''.join(rows)}
        </tbody>
    </table>

    {svg_section}

    <h2>CPU Flags</h2>
    <p><code>{cpu_flags}</code></p>
</body>
</html>
"""

    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        f.write(html)
    print(f"HTML report written to: {output_path}")


if __name__ == "__main__":
    input_path = sys.argv[1] if len(sys.argv) > 1 else "target/benchmark-results/benchmark-result.json"
    output_path = sys.argv[2] if len(sys.argv) > 2 else "target/benchmark-results/index.html"
    svg_path = sys.argv[3] if len(sys.argv) > 3 else None

    result = load_result(input_path)
    generate_html(result, output_path, svg_path=svg_path)

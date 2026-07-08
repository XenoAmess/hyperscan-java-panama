#!/usr/bin/env python3
import json
import os
import sys
from datetime import datetime, timezone
from html import escape


def load_results(input_dir):
    results = []
    for root, _, files in os.walk(input_dir):
        for name in files:
            if name.endswith('.json'):
                path = os.path.join(root, name)
                try:
                    with open(path, 'r', encoding='utf-8') as f:
                        data = json.load(f)
                        data['_source'] = path
                        results.append(data)
                except Exception as e:
                    print(f"Warning: failed to parse {path}: {e}", file=sys.stderr)
    return results


def safe_get(d, *keys, default=None):
    for key in keys:
        if isinstance(d, dict) and key in d:
            d = d[key]
        elif isinstance(d, list) and isinstance(key, int) and -len(d) <= key < len(d):
            d = d[key]
        else:
            return default
    return d


def format_num(value, decimals=2):
    if value is None:
        return 'N/A'
    try:
        return f"{float(value):,.{decimals}f}"
    except (ValueError, TypeError):
        return str(value)


def build_summary_rows(results, fixed_benchmark_name='ISA granularity benchmark'):
    rows = []
    for r in results:
        platform = r.get('platform', 'unknown')
        bench = next((b for b in r.get('benchmarks', []) if b.get('name') == fixed_benchmark_name), None)
        if bench is None:
            continue
        metrics = bench.get('metrics', {})
        throughput = metrics.get('throughputMBpsAvg', 0.0)
        rows.append({
            'platform': platform,
            'throughput': float(throughput or 0.0),
            'result': r,
        })
    rows.sort(key=lambda x: x['throughput'], reverse=True)
    return rows


def generate_svg(results, output_file, title='hyperscan-java-panama'):
    rows = build_summary_rows(results)
    if not rows:
        print('No fixed workload results found.', file=sys.stderr)
        sys.exit(1)

    max_tp = max(row['throughput'] for row in rows) if rows else 1
    if max_tp <= 0:
        max_tp = 1

    width = 800
    top_margin = 70
    bottom_margin = 40
    left_margin = 190
    right_margin = 90
    bar_height = 28
    bar_gap = 18
    chart_height = len(rows) * (bar_height + bar_gap) + bar_gap
    height = top_margin + chart_height + bottom_margin

    generated_at = datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC')
    native_version = safe_get(rows[0], 'result', 'nativeVersion', default='unknown') if rows else 'unknown'
    commit_sha = safe_get(rows[0], 'result', 'commitSha', default='unknown') if rows else 'unknown'
    commit_short = (commit_sha[:7] if commit_sha and len(commit_sha) > 7 else commit_sha) or 'unknown'

    svg = []
    svg.append(f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}" role="img" aria-label="Cross-platform performance summary">')
    svg.append('  <defs>')
    svg.append('    <linearGradient id="barGradient" x1="0%" y1="0%" x2="100%" y2="0%">')
    svg.append('      <stop offset="0%" style="stop-color:#2ea043;stop-opacity:1" />')
    svg.append('      <stop offset="100%" style="stop-color:#3fb950;stop-opacity:1" />')
    svg.append('    </linearGradient>')
    svg.append('  </defs>')
    svg.append(f'  <rect width="{width}" height="{height}" fill="#f6f8fa" rx="6" />')
    svg.append(f'  <text x="{width / 2}" y="30" font-family="-apple-system, BlinkMacSystemFont, Segoe UI, Roboto, Helvetica, Arial, sans-serif" font-size="18" font-weight="bold" text-anchor="middle" fill="#1f2328">{escape(title)} Fixed Workload</text>')
    subtitle = f"Native {native_version}  ·  {len(rows)} platforms  ·  commit {commit_short}"
    svg.append(f'  <text x="{width / 2}" y="52" font-family="-apple-system, BlinkMacSystemFont, Segoe UI, Roboto, Helvetica, Arial, sans-serif" font-size="11" text-anchor="middle" fill="#656d76">{escape(subtitle)}</text>')

    chart_width = width - left_margin - right_margin
    for idx, row in enumerate(rows):
        y = top_margin + bar_gap + idx * (bar_height + bar_gap)
        platform = row['platform']
        throughput = row['throughput']
        bar_width = (throughput / max_tp) * chart_width

        label_y = y + bar_height / 2 + 4
        svg.append(f'  <text x="{left_margin - 10}" y="{label_y}" font-family="-apple-system, BlinkMacSystemFont, Segoe UI, Roboto, Helvetica, Arial, sans-serif" font-size="12" font-weight="600" text-anchor="end" fill="#24292f">{escape(platform)}</text>')
        svg.append(f'  <rect x="{left_margin}" y="{y}" width="{chart_width}" height="{bar_height}" fill="#e1e4e8" rx="4" />')
        if bar_width > 0:
            fill = 'url(#barGradient)' if idx == 0 else '#2ea043'
            svg.append(f'  <rect x="{left_margin}" y="{y}" width="{bar_width:.1f}" height="{bar_height}" fill="{fill}" rx="4" />')
        value_text = f'{format_num(throughput)} MB/s'
        value_x = left_margin + bar_width + 8
        if bar_width + 80 > chart_width:
            value_x = left_margin + chart_width + 8
        svg.append(f'  <text x="{value_x}" y="{label_y}" font-family="-apple-system, BlinkMacSystemFont, Segoe UI, Roboto, Helvetica, Arial, sans-serif" font-size="12" font-weight="600" fill="#24292f">{escape(value_text)}</text>')

    footer_y = height - 15
    svg.append(f'  <text x="{width / 2}" y="{footer_y}" font-family="-apple-system, BlinkMacSystemFont, Segoe UI, Roboto, Helvetica, Arial, sans-serif" font-size="10" text-anchor="middle" fill="#656d76">Generated {escape(generated_at)}  ·  Full report at xenoamess.github.io/hyperscan-java-panama</text>')
    svg.append('</svg>')

    os.makedirs(os.path.dirname(output_file) or '.', exist_ok=True)
    with open(output_file, 'w', encoding='utf-8') as f:
        f.write('\n'.join(svg))
    print(f'Performance summary SVG generated: {output_file}')


def main():
    if len(sys.argv) < 3:
        print('Usage: generate-performance-svg.py <input-dir> <output-svg>', file=sys.stderr)
        sys.exit(1)
    input_dir = sys.argv[1]
    output_file = sys.argv[2]

    results = load_results(input_dir)
    if not results:
        print('No benchmark results found in input directory.', file=sys.stderr)
        sys.exit(1)

    generate_svg(results, output_file)


if __name__ == '__main__':
    main()

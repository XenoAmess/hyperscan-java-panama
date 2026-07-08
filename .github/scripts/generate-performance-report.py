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


def collect_benchmarks(results):
    """Group benchmark results by benchmark name across platforms."""
    by_name = {}
    for r in results:
        platform = r.get('platform', 'unknown')
        for bench in r.get('benchmarks', []):
            name = bench.get('name', 'unknown')
            metrics = bench.get('metrics', {})
            by_name.setdefault(name, []).append({
                'platform': platform,
                'metrics': metrics,
                'result': r,
            })
    return by_name


def build_fixed_workload_rows(results, fixed_benchmark_name='ISA granularity benchmark'):
    rows = []
    for r in results:
        platform = r.get('platform', 'unknown')
        bench = None
        for b in r.get('benchmarks', []):
            if b.get('name') == fixed_benchmark_name:
                bench = b
                break
        if bench is None:
            continue
        metrics = bench.get('metrics', {})
        throughput = metrics.get('throughputMBpsAvg', 0.0)
        elapsed = metrics.get('elapsedMsAvg', 0.0)
        rows.append({
            'platform': platform,
            'throughput': float(throughput or 0.0),
            'elapsed': float(elapsed or 0.0),
            'result': r,
        })
    rows.sort(key=lambda x: x['throughput'], reverse=True)
    return rows


def generate_html(results, output_file, title='hyperscan-java-panama Performance Report'):
    generated_at = datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC')
    native_version = safe_get(results, 0, 'nativeVersion', default='unknown') if results else 'unknown'
    commit_sha = safe_get(results, 0, 'commitSha', default='unknown') if results else 'unknown'
    commit_short = (commit_sha[:7] if commit_sha and len(commit_sha) > 7 else commit_sha) or 'unknown'

    by_benchmark = collect_benchmarks(results)
    fixed_name = 'ISA granularity benchmark'
    fixed_rows = build_fixed_workload_rows(results, fixed_name)

    html = []
    html.append('<!DOCTYPE html>')
    html.append('<html lang="en">')
    html.append('<head>')
    html.append('  <meta charset="UTF-8">')
    html.append('  <meta name="viewport" content="width=device-width, initial-scale=1.0">')
    html.append(f'  <title>{escape(title)}</title>')
    html.append('  <style>')
    html.append('''
    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; margin: 0; padding: 2rem; background: #f6f8fa; color: #24292f; }
    .container { max-width: 1400px; margin: 0 auto; background: #fff; border-radius: 8px; padding: 2rem; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
    h1 { margin-top: 0; color: #1f2328; }
    h2 { border-bottom: 1px solid #d0d7de; padding-bottom: 0.5rem; margin-top: 2.5rem; }
    h3 { margin-top: 1.5rem; color: #1f2328; }
    .meta { color: #656d76; margin-bottom: 1.5rem; }
    .meta span { margin-right: 1.5rem; }
    table { width: 100%; border-collapse: collapse; margin-top: 1rem; }
    th, td { padding: 0.6rem; text-align: left; border: 1px solid #d0d7de; }
    th { background: #f6f8fa; font-weight: 600; }
    tr:nth-child(even) { background: #fafafa; }
    .best { background: #dafbe1 !important; font-weight: 600; }
    .numeric { text-align: right; }
    .bar-bg { width: 100%; background: #e1e4e8; border-radius: 4px; height: 1rem; }
    .bar-fill { background: #2ea043; height: 100%; border-radius: 4px; }
    .summary-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 1rem; margin-bottom: 1.5rem; }
    .summary-item { background: #f6f8fa; border-radius: 6px; padding: 1rem; }
    .summary-item .label { font-size: 0.85rem; color: #656d76; }
    .summary-item .value { font-size: 1.4rem; font-weight: 700; margin-top: 0.25rem; }
    .card { border: 1px solid #d0d7de; border-radius: 6px; padding: 1rem; margin-bottom: 1rem; background: #fff; }
    .card h3 { margin-top: 0; }
    .metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 0.5rem; }
    .metric { padding: 0.5rem; background: #f6f8fa; border-radius: 4px; }
    .metric-label { font-size: 0.85rem; color: #656d76; }
    .metric-value { font-size: 1.1rem; font-weight: 600; }
    img { max-width: 100%; height: auto; border: 1px solid #d0d7de; border-radius: 4px; }
    code { background: #f0f0f0; padding: 0.1rem 0.3rem; border-radius: 3px; }
    ''')
    html.append('  </style>')
    html.append('</head>')
    html.append('<body>')
    html.append('  <div class="container">')
    html.append(f'    <h1>{escape(title)}</h1>')
    html.append('    <div class="meta">')
    html.append(f'      <span><strong>Generated:</strong> {escape(generated_at)}</span>')
    html.append(f'      <span><strong>Native Version:</strong> {escape(native_version)}</span>')
    html.append(f'      <span><strong>Commit:</strong> {escape(commit_short)}</span>')
    html.append(f'      <span><strong>Platforms:</strong> {len(results)}</span>')
    html.append('    </div>')

    # Executive summary based on fixed workload
    html.append('    <h2>Executive Summary</h2>')
    html.append('    <div class="summary-grid">')
    best = fixed_rows[0] if fixed_rows else None
    worst = fixed_rows[-1] if fixed_rows else None
    html.append('      <div class="summary-item">')
    html.append('        <div class="label">Best throughput</div>')
    html.append(f'        <div class="value">{escape(best["platform"] if best else "N/A")}</div>')
    html.append(f'        <div class="label">{format_num(best["throughput"] if best else None)} MB/s</div>')
    html.append('      </div>')
    html.append('      <div class="summary-item">')
    html.append('        <div class="label">Worst throughput</div>')
    html.append(f'        <div class="value">{escape(worst["platform"] if worst else "N/A")}</div>')
    html.append(f'        <div class="label">{format_num(worst["throughput"] if worst else None)} MB/s</div>')
    html.append('      </div>')
    html.append('      <div class="summary-item">')
    html.append('        <div class="label">Performance range</div>')
    if best and worst and worst['throughput'] > 0:
        ratio = best['throughput'] / worst['throughput']
    else:
        ratio = None
    html.append(f'        <div class="value">{format_num(ratio, 2)}x</div>')
    html.append('        <div class="label">best vs worst</div>')
    html.append('      </div>')
    workload = 'N/A'
    if fixed_rows:
        bench = next((b for b in results[0].get('benchmarks', []) if b.get('name') == fixed_name), {})
        metrics = bench.get('metrics', {})
        workload = f"{metrics.get('patterns', 'N/A')} patterns / {metrics.get('inputBytes', 'N/A')} bytes"
    html.append('      <div class="summary-item">')
    html.append('        <div class="label">Fixed workload</div>')
    html.append(f'        <div class="value">{escape(workload)}</div>')
    html.append('        <div class="label">per iteration</div>')
    html.append('      </div>')
    html.append('    </div>')

    html.append('    <h2>Fixed Workload Cross-Platform Comparison</h2>')
    html.append('    <p>')
    html.append('      Same workload used by <a href="https://xenoamess.github.io/hyperscan-java-test/">hyperscan-java-test</a>:')
    html.append('      500 mixed patterns over ~20 KB of input, 5 measured iterations.')
    html.append('    </p>')
    html.append('    <img src="summary.svg" alt="Fixed workload throughput comparison" />')
    html.append('    <table>')
    html.append('      <tr>')
    html.append('        <th>Rank</th>')
    html.append('        <th>Platform</th>')
    html.append('        <th>Runner OS / Arch</th>')
    html.append('        <th>CPU</th>')
    html.append('        <th class="numeric">Throughput (MB/s)</th>')
    html.append('        <th class="numeric">Elapsed (ms)</th>')
    html.append('        <th class="numeric">Relative</th>')
    html.append('      </tr>')
    for idx, row in enumerate(fixed_rows, start=1):
        r = row['result']
        platform = row['platform']
        runner_os = r.get('runnerOs', '-')
        runner_arch = r.get('runnerArch', '-')
        cpu_model = r.get('cpuModel', '-')
        cpu_flags = r.get('cpuFlags', '-')
        cpu_display = cpu_model if cpu_model and cpu_model != '-' else cpu_flags
        throughput = format_num(row['throughput'])
        elapsed = format_num(row['elapsed'])
        relative = ''
        if best and best['throughput'] > 0 and row['throughput'] > 0:
            pct = row['throughput'] / best['throughput'] * 100
            relative = f'{format_num(pct, 1)}%'
        cls = 'best' if idx == 1 else ''
        html.append(f'      <tr class="{cls}">')
        html.append(f'        <td>{idx}</td>')
        html.append(f'        <td>{escape(platform)}</td>')
        html.append(f'        <td>{escape(runner_os)} / {escape(runner_arch)}</td>')
        html.append(f'        <td title="{escape(cpu_flags)}">{escape(str(cpu_display)[:60])}</td>')
        html.append(f'        <td class="numeric">{escape(throughput)}</td>')
        html.append(f'        <td class="numeric">{escape(elapsed)}</td>')
        html.append(f'        <td class="numeric">{escape(relative)}</td>')
        html.append('      </tr>')
    html.append('    </table>')

    # Per-benchmark cross-platform comparison tables
    html.append('    <h2>Per-Benchmark Cross-Platform Comparison</h2>')
    for bench_name, rows in sorted(by_benchmark.items()):
        rows = sorted(rows, key=lambda x: safe_get(x, 'metrics', 'throughputMBpsAvg', default=0.0) or
                                           safe_get(x, 'metrics', 'opsPerSecond', default=0.0) or
                                           safe_get(x, 'metrics', 'elapsedMs', default=0.0), reverse=True)
        html.append(f'    <h3>{escape(bench_name)}</h3>')
        html.append('    <table>')
        html.append('      <tr>')
        html.append('        <th>Platform</th>')
        html.append('        <th>Runner OS / Arch</th>')
        html.append('        <th>CPU</th>')
        html.append('        <th class="numeric">Iterations</th>')
        html.append('        <th class="numeric">Elapsed (ms)</th>')
        html.append('        <th class="numeric">Throughput (MB/s)</th>')
        html.append('        <th class="numeric">Ops/Second</th>')
        html.append('        <th class="numeric">ns/Op</th>')
        html.append('        <th class="numeric">Total Matches</th>')
        html.append('      </tr>')
        for row in rows:
            r = row['result']
            platform = row['platform']
            metrics = row['metrics']
            runner_os = r.get('runnerOs', '-')
            runner_arch = r.get('runnerArch', '-')
            cpu_model = r.get('cpuModel', '-')
            cpu_flags = r.get('cpuFlags', '-')
            cpu_display = cpu_model if cpu_model and cpu_model != '-' else cpu_flags
            html.append('      <tr>')
            html.append(f'        <td>{escape(platform)}</td>')
            html.append(f'        <td>{escape(runner_os)} / {escape(runner_arch)}</td>')
            html.append(f'        <td title="{escape(cpu_flags)}">{escape(str(cpu_display)[:60])}</td>')
            html.append(f'        <td class="numeric">{format_num(metrics.get("iterations"))}</td>')
            html.append(f'        <td class="numeric">{format_num(metrics.get("elapsedMsAvg", metrics.get("elapsedMs")))}</td>')
            html.append(f'        <td class="numeric">{format_num(metrics.get("throughputMBpsAvg"))}</td>')
            html.append(f'        <td class="numeric">{format_num(metrics.get("opsPerSecond"))}</td>')
            html.append(f'        <td class="numeric">{format_num(metrics.get("nsPerOp"))}</td>')
            html.append(f'        <td class="numeric">{format_num(metrics.get("totalMatches"))}</td>')
            html.append('      </tr>')
        html.append('    </table>')

    # Per-platform details
    html.append('    <h2>Per-Platform Details</h2>')
    for r in sorted(results, key=lambda x: x.get('platform', '')):
        platform = r.get('platform', 'unknown')
        html.append(f'    <div class="card">')
        html.append(f'      <h3>{escape(platform)}</h3>')
        html.append('      <div class="metrics">')
        html.append('        <div class="metric">')
        html.append('          <div class="metric-label">OS / Arch</div>')
        html.append(f'          <div class="metric-value">{escape(r.get("runnerOs", "-"))} / {escape(r.get("runnerArch", "-"))}</div>')
        html.append('        </div>')
        html.append('        <div class="metric">')
        html.append('          <div class="metric-label">CPU</div>')
        html.append(f'          <div class="metric-value">{escape(str(r.get("cpuModel", "-"))[:60])}</div>')
        html.append('        </div>')
        html.append('        <div class="metric">')
        html.append('          <div class="metric-label">Native Version</div>')
        html.append(f'          <div class="metric-value">{escape(r.get("nativeVersion", "-"))}</div>')
        html.append('        </div>')
        html.append('      </div>')
        for bench in r.get('benchmarks', []):
            html.append(f'      <h4>{escape(bench.get("name", "unknown"))}</h4>')
            html.append('      <div class="metrics">')
            for key, value in bench.get('metrics', {}).items():
                html.append('        <div class="metric">')
                html.append(f'          <div class="metric-label">{escape(key)}</div>')
                html.append(f'          <div class="metric-value">{escape(format_num(value))}</div>')
                html.append('        </div>')
            html.append('      </div>')
        html.append('    </div>')

    # Raw data
    html.append('    <h2>Raw Data</h2>')
    html.append('    <p>The following raw JSON files were aggregated to produce this report.</p>')
    html.append('    <ul>')
    for r in results:
        platform = r.get('platform', 'unknown')
        html.append(f'      <li>{escape(platform)}</li>')
    html.append('    </ul>')

    html.append('  </div>')
    html.append('</body>')
    html.append('</html>')

    os.makedirs(os.path.dirname(output_file) or '.', exist_ok=True)
    with open(output_file, 'w', encoding='utf-8') as f:
        f.write('\n'.join(html))
    print(f'Aggregated HTML report written to: {output_file}')


def main():
    if len(sys.argv) < 3:
        print('Usage: generate-performance-report.py <input-dir> <output-html>', file=sys.stderr)
        sys.exit(1)
    input_dir = sys.argv[1]
    output_file = sys.argv[2]

    results = load_results(input_dir)
    if not results:
        print('No benchmark results found in input directory.', file=sys.stderr)
        sys.exit(1)

    generate_html(results, output_file)


if __name__ == '__main__':
    main()

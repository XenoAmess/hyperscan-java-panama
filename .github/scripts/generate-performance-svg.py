#!/usr/bin/env python3
import json
import math
import os
import sys


def load_result(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def escape_xml(text):
    return (text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace('"', "&quot;"))


def generate_svg(result, output_path, metric="opsPerSecond"):
    benchmarks = result.get("benchmarks", [])
    if not benchmarks:
        print("No benchmarks to chart")
        return

    names = [b.get("name", "") for b in benchmarks]
    values = [b.get("metrics", {}).get(metric, 0) for b in benchmarks]

    width = 900
    left_margin = 200
    right_margin = 40
    top_margin = 60
    bottom_margin = 80
    chart_width = width - left_margin - right_margin
    chart_height = 400
    height = chart_height + top_margin + bottom_margin

    max_value = max(values) if values else 0
    if max_value == 0:
        max_value = 1

    # Round max up to a nice number
    magnitude = 10 ** math.floor(math.log10(max_value)) if max_value > 0 else 1
    nice_max = math.ceil(max_value / magnitude) * magnitude

    bar_width = chart_width / len(benchmarks) * 0.7
    bar_gap = chart_width / len(benchmarks) * 0.3
    bar_unit_height = chart_height / nice_max

    bars = []
    labels = []
    for i, (name, value) in enumerate(zip(names, values)):
        x = left_margin + i * (bar_width + bar_gap) + bar_gap / 2
        bar_h = value * bar_unit_height
        y = top_margin + chart_height - bar_h
        bars.append(
            f'<rect x="{x:.2f}" y="{y:.2f}" width="{bar_width:.2f}" height="{bar_h:.2f}" fill="#4f81bd" />'
        )
        # value label on top of bar
        labels.append(
            f'<text x="{x + bar_width / 2:.2f}" y="{y - 5:.2f}" text-anchor="middle" font-size="12">{value:.2f}</text>'
        )
        # x-axis label rotated
        labels.append(
            f'<text x="{x + bar_width / 2:.2f}" y="{top_margin + chart_height + 15:.2f}" '
            f'text-anchor="end" font-size="12" transform="rotate(-45, {x + bar_width / 2:.2f}, {top_margin + chart_height + 15:.2f})">'
            f'{escape_xml(name)}</text>'
        )

    # y-axis grid lines and labels
    grid_lines = []
    for i in range(6):
        value = nice_max * i / 5
        y = top_margin + chart_height - (value * bar_unit_height)
        grid_lines.append(
            f'<line x1="{left_margin:.2f}" y1="{y:.2f}" x2="{width - right_margin:.2f}" y2="{y:.2f}" stroke="#ddd" stroke-width="1" />'
        )
        grid_lines.append(
            f'<text x="{left_margin - 10:.2f}" y="{y + 4:.2f}" text-anchor="end" font-size="12">{value:.2f}</text>'
        )

    svg = f"""<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">
  <rect width="100%" height="100%" fill="white"/>
  <text x="{width / 2:.2f}" y="30" text-anchor="middle" font-size="18" font-weight="bold">{escape_xml(metric)} per Benchmark</text>
  {''.join(grid_lines)}
  <line x1="{left_margin:.2f}" y1="{top_margin:.2f}" x2="{left_margin:.2f}" y2="{top_margin + chart_height:.2f}" stroke="#333" stroke-width="2"/>
  <line x1="{left_margin:.2f}" y1="{top_margin + chart_height:.2f}" x2="{width - right_margin:.2f}" y2="{top_margin + chart_height:.2f}" stroke="#333" stroke-width="2"/>
  {''.join(bars)}
  {''.join(labels)}
</svg>
"""

    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        f.write(svg)
    print(f"SVG chart written to: {output_path}")


if __name__ == "__main__":
    input_path = sys.argv[1] if len(sys.argv) > 1 else "target/benchmark-results/benchmark-result.json"
    output_path = sys.argv[2] if len(sys.argv) > 2 else "target/benchmark-results/chart.svg"
    metric = sys.argv[3] if len(sys.argv) > 3 else "opsPerSecond"

    result = load_result(input_path)
    generate_svg(result, output_path, metric=metric)

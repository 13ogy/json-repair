#!/usr/bin/env python3
# Génère les figures du rapport à partir des CSV produits par les harnais.
# Lecture : data/*.csv. Écriture : report/img/*.png.
import csv
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data"
OUT = ROOT / "report" / "img"
OUT.mkdir(parents=True, exist_ok=True)

COLOR_GOOD = "#2ca02c"
COLOR_MID = "#ff7f0e"
COLOR_BAD = "#d62728"
COLOR_NEUTRAL = "#1f77b4"
COLOR_GREY = "#7f7f7f"


def color_by_rate(rate):
    if rate >= 90:
        return COLOR_GOOD
    if rate >= 60:
        return COLOR_MID
    return COLOR_BAD


def read_csv(path):
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


official = read_csv(DATA / "official_test_suite_results.csv")
stress = read_csv(DATA / "stress_test_results.csv")
manual_summary = read_csv(DATA / "manual_tests_summary.csv")
manual_distances = read_csv(DATA / "manual_tests_distances.csv")


def parse_int(v, default=0):
    try:
        return int(v)
    except (ValueError, TypeError):
        return default


def parse_float(v, default=0.0):
    try:
        return float(v)
    except (ValueError, TypeError):
        return default


official_supported = [
    r for r in official if r["supported"] == "true" and r["rate_percent"] != ""
]
official_unsupported = [
    r for r in official if r["supported"] == "false" and r["rate_percent"] != ""
]


def plot_repair_rate_supported():
    rows = sorted(official_supported, key=lambda r: parse_int(r["rate_percent"]), reverse=True)
    keywords = [r["keyword"] for r in rows]
    rates = [parse_int(r["rate_percent"]) for r in rows]
    totals = [parse_int(r["total"]) for r in rows]
    colors = [color_by_rate(r) for r in rates]

    fig, ax = plt.subplots(figsize=(11, 6.5))
    bars = ax.bar(keywords, rates, color=colors, edgecolor="black", linewidth=0.4)

    for bar, rate, total, repaired in zip(
        bars, rates, totals, [parse_int(r["repaired"]) for r in rows]
    ):
        h = bar.get_height()
        ax.annotate(
            f"{rate}%\n({repaired}/{total})",
            xy=(bar.get_x() + bar.get_width() / 2, h),
            xytext=(0, 3),
            textcoords="offset points",
            ha="center",
            va="bottom",
            fontsize=7.5,
        )

    ax.set_ylabel("Taux de réparation (%)")
    ax.set_xlabel("Mot-clé JSON Schema")
    ax.set_title("Taux de réparation par mot-clé — JSON Schema Test Suite (draft 2020-12)")
    ax.set_ylim(0, 115)
    ax.set_yticks(range(0, 101, 20))
    plt.setp(ax.get_xticklabels(), rotation=40, ha="right", fontsize=9)

    from matplotlib.patches import Patch

    legend = [
        Patch(facecolor=COLOR_GOOD, label="≥ 90%"),
        Patch(facecolor=COLOR_MID, label="60–89%"),
        Patch(facecolor=COLOR_BAD, label="< 60%"),
    ]
    ax.legend(handles=legend, loc="upper right", framealpha=0.9, fontsize=9)
    ax.set_axisbelow(True)
    ax.grid(axis="y", linestyle="--", alpha=0.5)

    plt.tight_layout()
    out = OUT / "repair_rate_supported.png"
    plt.savefig(out, dpi=150, facecolor="white")
    plt.close()
    print(f"  - {out}")


def plot_repair_rate_unsupported():
    rows = sorted(official_unsupported, key=lambda r: parse_int(r["rate_percent"]), reverse=True)
    keywords = [r["keyword"] for r in rows]
    rates = [parse_int(r["rate_percent"]) for r in rows]
    totals = [parse_int(r["total"]) for r in rows]
    colors = [color_by_rate(r) for r in rates]

    fig, ax = plt.subplots(figsize=(10, 5.5))
    bars = ax.bar(keywords, rates, color=colors, edgecolor="black", linewidth=0.4)

    for bar, rate, total, repaired in zip(
        bars, rates, totals, [parse_int(r["repaired"]) for r in rows]
    ):
        h = bar.get_height()
        ax.annotate(
            f"{rate}%\n({repaired}/{total})",
            xy=(bar.get_x() + bar.get_width() / 2, h),
            xytext=(0, 3),
            textcoords="offset points",
            ha="center",
            va="bottom",
            fontsize=7.5,
        )

    ax.set_ylabel("Taux de réparation (%)")
    ax.set_xlabel("Mot-clé non supporté")
    ax.set_title("Couverture best-effort — mots-clés non implémentés explicitement")
    ax.set_ylim(0, 100)
    plt.setp(ax.get_xticklabels(), rotation=40, ha="right", fontsize=9)
    ax.set_axisbelow(True)
    ax.grid(axis="y", linestyle="--", alpha=0.5)

    plt.tight_layout()
    out = OUT / "repair_rate_unsupported.png"
    plt.savefig(out, dpi=150, facecolor="white")
    plt.close()
    print(f"  - {out}")


def plot_coverage_pie():
    sup_total = sum(parse_int(r["total"]) for r in official if r["supported"] == "true")
    sup_rep = sum(parse_int(r["repaired"]) for r in official if r["supported"] == "true")
    unsup_total = sum(parse_int(r["total"]) for r in official if r["supported"] == "false")
    unsup_rep = sum(parse_int(r["repaired"]) for r in official if r["supported"] == "false")

    sup_failed = sup_total - sup_rep
    unsup_failed = unsup_total - unsup_rep

    labels = [
        f"Réparés (supportés)\n{sup_rep}",
        f"Échecs (supportés)\n{sup_failed}",
        f"Réparés best-effort\n(non supportés)\n{unsup_rep}",
        f"Échecs (non supportés)\n{unsup_failed}",
    ]
    sizes = [sup_rep, sup_failed, unsup_rep, unsup_failed]
    colors = [COLOR_GOOD, COLOR_MID, "#9edae5", COLOR_GREY]

    fig, ax = plt.subplots(figsize=(8.5, 6.5))
    wedges, texts, autotexts = ax.pie(
        sizes,
        labels=labels,
        colors=colors,
        autopct=lambda p: f"{p:.1f}%" if p > 1 else "",
        startangle=90,
        wedgeprops=dict(edgecolor="white", linewidth=1.2),
        textprops=dict(fontsize=9),
    )
    for at in autotexts:
        at.set_color("white")
        at.set_fontweight("bold")
        at.set_fontsize(10)

    total = sup_total + unsup_total
    overall_rep = sup_rep + unsup_rep
    ax.set_title(
        f"Couverture globale — JSON Schema Test Suite (draft 2020-12)\n"
        f"{overall_rep}/{total} instances réparées sur l'ensemble"
        f" ({overall_rep * 100 / total:.1f}%)",
        fontsize=11,
    )

    plt.tight_layout()
    out = OUT / "coverage_pie.png"
    plt.savefig(out, dpi=150, facecolor="white")
    plt.close()
    print(f"  - {out}")


def plot_stress_test():
    rows = [r for r in stress if r["collection"] != "TOTAL"]
    rows.sort(key=lambda r: r["collection"])

    collections = [r["collection"] for r in rows]
    totals = [parse_int(r["total"]) for r in rows]
    generated = [parse_int(r["generated"]) for r in rows]
    mutated = [parse_int(r["mutated"]) for r in rows]
    repaired = [parse_int(r["repaired"]) for r in rows]

    x = np.arange(len(collections))
    w = 0.22

    fig, ax = plt.subplots(figsize=(12, 6))
    b1 = ax.bar(x - 1.5 * w, totals, w, label="Échantillon", color=COLOR_GREY, edgecolor="black", linewidth=0.3)
    b2 = ax.bar(x - 0.5 * w, generated, w, label="Instance générée", color=COLOR_NEUTRAL, edgecolor="black", linewidth=0.3)
    b3 = ax.bar(x + 0.5 * w, mutated, w, label="Mutation invalide", color=COLOR_MID, edgecolor="black", linewidth=0.3)
    b4 = ax.bar(x + 1.5 * w, repaired, w, label="Réparée avec succès", color=COLOR_GOOD, edgecolor="black", linewidth=0.3)

    for bars in (b1, b2, b3, b4):
        for bar in bars:
            h = bar.get_height()
            if h > 0:
                ax.annotate(
                    f"{int(h)}",
                    xy=(bar.get_x() + bar.get_width() / 2, h),
                    xytext=(0, 2),
                    textcoords="offset points",
                    ha="center",
                    va="bottom",
                    fontsize=7,
                )

    ax.set_ylabel("Nombre de schémas")
    ax.set_xlabel("Collection")
    ax.set_title("Stress test sur jsonschemabench — pipeline génération → mutation → réparation")
    ax.set_xticks(x)
    ax.set_xticklabels(collections, rotation=25, ha="right", fontsize=9)
    ax.legend(loc="upper right", fontsize=9, framealpha=0.95)
    ax.set_axisbelow(True)
    ax.grid(axis="y", linestyle="--", alpha=0.5)

    plt.tight_layout()
    out = OUT / "stress_test_breakdown.png"
    plt.savefig(out, dpi=150, facecolor="white")
    plt.close()
    print(f"  - {out}")


def plot_distances_histogram():
    distances = [
        parse_int(r["distance"]) for r in manual_distances if r["status"] == "repaired"
    ]
    if not distances:
        print("  (histogramme ignoré : pas de données)")
        return

    max_d = max(distances)
    bins = np.arange(0, max_d + 2) - 0.5

    fig, ax = plt.subplots(figsize=(8, 5))
    counts, edges, patches = ax.hist(
        distances, bins=bins, color=COLOR_NEUTRAL, edgecolor="black", linewidth=0.5
    )

    for c, e in zip(counts, edges[:-1]):
        if c > 0:
            ax.annotate(
                f"{int(c)}",
                xy=(e + 0.5, c),
                xytext=(0, 3),
                textcoords="offset points",
                ha="center",
                va="bottom",
                fontsize=10,
            )

    avg = sum(distances) / len(distances)
    ax.axvline(avg, color=COLOR_BAD, linestyle="--", linewidth=1.5, label=f"Moyenne = {avg:.2f}")

    ax.set_xlabel("Distance Diffson (nombre d'opérations du JSON Patch)")
    ax.set_ylabel("Nombre d'instances")
    ax.set_title(
        f"Minimalité des réparations — jeu de tests manuel ({len(distances)} instances)"
    )
    ax.set_xticks(range(0, max_d + 1))
    ax.legend(fontsize=10)
    ax.set_axisbelow(True)
    ax.grid(axis="y", linestyle="--", alpha=0.5)

    plt.tight_layout()
    out = OUT / "manual_tests_distances.png"
    plt.savefig(out, dpi=150, facecolor="white")
    plt.close()
    print(f"  - {out}")


def plot_keyword_families():
    families = {
        "Scalaires": [
            "type", "const", "enum",
            "minimum", "maximum",
            "exclusiveMinimum", "exclusiveMaximum",
            "multipleOf", "minLength", "maxLength", "pattern",
        ],
        "Structurels": [
            "required", "properties", "additionalProperties",
            "minProperties", "maxProperties",
            "minItems", "maxItems", "uniqueItems",
            "items", "prefixItems",
        ],
        "Combinateurs": [
            "anyOf", "oneOf", "allOf", "not", "if-then-else",
        ],
    }

    by_keyword = {r["keyword"]: r for r in official_supported}

    family_names = []
    family_rates = []
    family_totals = []
    family_repaired = []

    for fname, kws in families.items():
        total = sum(parse_int(by_keyword[k]["total"]) for k in kws if k in by_keyword)
        repaired = sum(parse_int(by_keyword[k]["repaired"]) for k in kws if k in by_keyword)
        if total > 0:
            family_names.append(fname)
            family_rates.append(repaired * 100 / total)
            family_totals.append(total)
            family_repaired.append(repaired)

    colors = [color_by_rate(r) for r in family_rates]

    fig, ax = plt.subplots(figsize=(7.5, 5))
    bars = ax.bar(family_names, family_rates, color=colors, edgecolor="black", linewidth=0.4, width=0.55)

    for bar, rate, total, rep in zip(bars, family_rates, family_totals, family_repaired):
        h = bar.get_height()
        ax.annotate(
            f"{rate:.1f}%\n({rep}/{total})",
            xy=(bar.get_x() + bar.get_width() / 2, h),
            xytext=(0, 4),
            textcoords="offset points",
            ha="center",
            va="bottom",
            fontsize=10,
        )

    ax.set_ylabel("Taux de réparation moyen (%)")
    ax.set_xlabel("Famille de mots-clés")
    ax.set_title("Comparaison par famille — JSON Schema Test Suite (draft 2020-12)")
    ax.set_ylim(0, 115)
    ax.set_axisbelow(True)
    ax.grid(axis="y", linestyle="--", alpha=0.5)

    plt.tight_layout()
    out = OUT / "keyword_categories.png"
    plt.savefig(out, dpi=150, facecolor="white")
    plt.close()
    print(f"  - {out}")


if __name__ == "__main__":
    print(f"Génération des figures dans {OUT}")
    plot_repair_rate_supported()
    plot_repair_rate_unsupported()
    plot_coverage_pie()
    plot_stress_test()
    plot_distances_histogram()
    plot_keyword_families()

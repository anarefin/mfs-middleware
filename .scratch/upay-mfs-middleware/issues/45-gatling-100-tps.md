# 45: Gatling 100 TPS mix

**What to build:** The single MVP host handles at least 100 TPS of mixed load with p95 middleware overhead under 150 ms (acceptance criterion 7, a sanity check). `make perf` runs a Gatling scenario mix across the MVP flows. Overhead is measured excluding core-mock and simulator time. Spec WP28.

**Blocked by:**

- 43: E2E acceptance suite completion (criteria 1–5)

**Status:** ready-for-agent

- [ ] The Gatling report meets 100 TPS and p95 overhead < 150 ms, or the gap is documented with findings
- [ ] No errors other than intended business failures during the run
